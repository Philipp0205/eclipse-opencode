package com.opencode.eclipse.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ide.IDE;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opencode.eclipse.core.CommandInfo;

/**
 * Routes Eclipse-owned slash commands (`/rename`, `/diff`, `/editor`, `/move`, `/restart`, ...)
 * to their handlers, and merges them with server-provided project/skill/MCP-prompt commands.
 *
 * <p>Split out of {@link ChatView} so the routing table and each command's implementation live
 * in one place separate from widget construction and turn/streaming orchestration. This class
 * still touches several {@link ChatView} widgets/state directly (via constructor-injected
 * package-private access, the same compromise used by {@link ChatViewProbe}) rather than a full
 * SWT-free callback interface — see {@code ChatController}/{@code ChatViewCallbacks} in a future
 * phase for that.
 */
final class CommandRouter {
	private static final List<CommandInfo> CLIENT_COMMANDS = List.of(
			new CommandInfo("model", "Open the Eclipse model picker", "client", null, null, false, List.of()),
			new CommandInfo("models", "Open the Eclipse model picker", "client", null, null, false, List.of()),
			new CommandInfo("agents", "Open the agent selector", "client", null, null, false, List.of()),
			new CommandInfo("sessions", "Open the session selector", "client", null, null, false, List.of()),
			new CommandInfo("new", "Start a new OpenCode session", "client", null, null, false, List.of()),
			new CommandInfo("compact", "Compact the current OpenCode session", "client", null, null, false, List.of()),
			new CommandInfo("move", "Move the current session to another directory", "client", null, null, false, List.of()),
			new CommandInfo("restart", "Restart OpenCode to apply configuration changes", "client", null, null, false, List.of()),
			new CommandInfo("mcps", "Show OpenCode MCP server status", "client", null, null, false, List.of()),
			new CommandInfo("permissions", "Forget remembered permission answers", "client", null, null, false, List.of()),
			new CommandInfo("help", "Show available Eclipse slash commands", "client", null, null, false, List.of()));
	// These names intentionally win over server commands with the same name.
	private static final List<CommandInfo> PHASE_ONE_COMMANDS = List.of(
			new CommandInfo("rename", "Rename the current session", "client", null, null, false, List.of()),
			new CommandInfo("fork", "Fork the current session", "client", null, null, false, List.of()),
			new CommandInfo("diff", "Show the authoritative session diff", "client", null, null, false, List.of()),
			new CommandInfo("editor", "Compose prompt text in an Eclipse editor", "client", null, null, false, List.of()));
	private static final CommandInfo CONNECT_COMMAND =
			new CommandInfo("connect", "Connect an AI provider", "client", null, null, false, List.of());

	private final ChatView view;

	CommandRouter(ChatView view) {
		this.view = view;
	}

	/** Merge Eclipse-owned client commands with server-provided ones; client commands always win
	 * on a name collision. */
	static List<CommandInfo> mergedCommands(List<CommandInfo> server) {
		LinkedHashMap<String, CommandInfo> merged = new LinkedHashMap<>();
		for (CommandInfo c : CLIENT_COMMANDS) merged.put(c.name(), c);
		for (CommandInfo c : PHASE_ONE_COMMANDS) merged.put(c.name(), c);
		merged.put(CONNECT_COMMAND.name(), CONNECT_COMMAND);
		for (CommandInfo c : server) merged.putIfAbsent(c.name(), c);
		return List.copyOf(merged.values());
	}

	void handle(String command, String arguments) {
		switch (command) {
			case "rename" -> renameSessionWithTitle(arguments);
			case "fork" -> view.sessionAction("fork");
			case "diff" -> showSessionDiff();
			case "editor" -> openPromptEditor(arguments);
			case "model", "models" -> view.openModelPicker();
			case "agents" -> { view.agentCombo.setFocus(); view.agentCombo.setListVisible(true); }
			case "sessions" -> view.openSessionPicker();
			case "new" -> view.newSessionAsync();
			case "compact" -> compactSessionAsync();
			case "move" -> moveSession();
			case "restart" -> restartOpenCode();
			case "mcps" -> new McpDialog(view.getSite().getShell(), view.service).open();
			case "permissions" -> view.setStatus("Forgot " + view.permissionDecisions.forgetAll()
					+ " remembered permission answer(s)");
			case "help" -> view.conversation.putMessage("client-help-" + System.nanoTime(), "assistant",
					"**Eclipse commands:** `/models`, `/agents`, `/sessions`, `/new`, `/move`, `/restart`, `/mcps`, "
					+ "`/permissions`, `/help`\n\n"
					+ "Project commands, MCP prompts, and skills are also available through `/`.");
			case "connect" -> new ConnectProviderDialog(view.getSite().getShell(), view.service,
					this::refreshProviderSetupAsync).open();
			default -> {
				// Not an Eclipse-owned client command; nothing to do here.
			}
		}
	}

	private void renameSessionWithTitle(String title) {
		if (title == null || title.isBlank()) {
			view.renameSession();
			return;
		}
		String id = view.service.getCurrentSessionId();
		new Thread(() -> {
			try {
				view.service.renameSession(id, title.trim());
				view.refreshSessionsAsync();
			} catch (Exception ex) {
				view.ui(() -> view.setStatus("Rename failed: " + ex.getMessage()));
			}
		}, "opencode-rename-command").start();
	}

	private void showSessionDiff() {
		new Thread(() -> {
			try {
				JsonArray diff = view.service.getDiff(null);
				view.ui(() -> {
					if (diff != null) view.diffs.setAuthoritativeChanges(diff, view.service.getWorkspaceRoot());
					if (diff == null || diff.size() == 0 || view.diffs.currentFiles().isEmpty()) {
						view.setStatus("No changes in this session");
					} else if (!Diffs.openListing(view.diffs)) view.setStatus("Diff failed: Unable to open diff listing");
				});
			} catch (Exception ex) {
				view.ui(() -> view.setStatus("Diff failed: " + ex.getMessage()));
			}
		}, "opencode-session-diff").start();
	}

	private void openPromptEditor(String initial) {
		try {
			IWorkspaceRoot wsRoot = ResourcesPlugin.getWorkspace().getRoot();
			var projects = wsRoot.getProjects();
			if (projects.length == 0) throw new IOException("No Eclipse project is available for the prompt editor");
			IProject project = projects[0];
			IFolder folder = project.getFolder(".opencode-prompts");
			if (!folder.exists()) folder.create(true, true, null);
			var promptFile = folder.getFile("prompt-" + System.nanoTime() + ".txt");
			promptFile.create(new ByteArrayInputStream((initial == null ? "" : initial)
					.getBytes(StandardCharsets.UTF_8)), true, null);
			Path file = promptFile.getLocation().toFile().toPath();
			IEditorPart editor = IDE.openEditor(view.getSite().getPage(), promptFile, true);
			editor.addPropertyListener((source, prop) -> {
				if (prop == IEditorPart.PROP_DIRTY && !editor.isDirty()) submitEditorFile(file);
			});
		} catch (Exception ex) {
			view.setStatus("Prompt editor failed: " + ex.getMessage());
		}
	}

	private void submitEditorFile(Path file) {
		try {
			String text = Files.readString(file);
			if (!text.isBlank()) view.ui(() -> {
				view.input.setText(text);
				view.input.setFocus();
				view.sendButton.notifyListeners(SWT.Selection, new Event());
			});
		} catch (Exception ignored) {
			// Best-effort; the editor stays open if the file can't be read.
		}
	}

	private void restartOpenCode() {
		if (view.busy || !view.promptQueue.isEmpty()) {
			view.setStatus("Finish current work before restarting OpenCode");
			return;
		}
		String session = view.service.getCurrentSessionId();
		view.setStatus("Restarting OpenCode…");
		view.spinner.start();
		new Thread(() -> {
			try {
				view.service.dispose();
				view.service.initialize(ChatView.workspaceRoot());
				try {
					if (session != null) view.service.switchSession(session);
					else view.service.createSession();
				} catch (Exception ignored) {
					view.service.createSession();
				}
				JsonArray sessions = view.service.listSessions();
				JsonArray messages = view.service.getMessages(view.service.getCurrentSessionId());
				List<JsonObject> agents = view.service.getAgents();
				JsonObject providers = view.service.listProviders();
				JsonObject config = view.service.getConfig();
				List<CommandInfo> loadedCommands = view.service.listCommands();
				view.ui(() -> {
					view.spinner.stop();
					view.fillSessions(sessions);
					view.renderHistory(messages);
					String defaultAgent = config.has("default_agent") ? config.get("default_agent").getAsString()
							: config.has("defaultAgent") ? config.get("defaultAgent").getAsString() : null;
					view.fillAgents(agents, defaultAgent);
					view.fillModels(providers, config.has("model") ? config.get("model").getAsString() : null);
					view.commands = mergedCommands(loadedCommands);
					view.service.watchSessionEvents(event -> view.ui(() -> view.controller.onSessionEvent(event)));
				});
			} catch (Exception ex) {
				view.ui(() -> {
					view.spinner.stop();
					view.setStatus("Restart failed: " + ex.getMessage());
				});
			}
		}, "opencode-restart").start();
	}

	private void moveSession() {
		DirectoryDialog dialog = new DirectoryDialog(view.getSite().getShell());
		dialog.setText("Move OpenCode session");
		dialog.setMessage("Select the destination directory");
		dialog.setFilterPath(view.service.getCurrentSessionDirectory());
		String destination = dialog.open();
		if (destination == null) return;
		try {
			Path root = Path.of(view.service.getWorkspaceRoot()).toRealPath();
			Path chosen = Path.of(destination).toRealPath();
			if (!chosen.startsWith(root)) {
				view.setStatus("Choose a directory inside the current OpenCode project");
				return;
			}
		} catch (IOException ex) {
			view.setStatus("Invalid destination: " + ex.getMessage());
			return;
		}
		String session = view.service.getCurrentSessionId();
		new Thread(() -> {
			try {
				view.service.moveSession(session, destination);
				view.ui(() -> {
					view.workingFolder = destination;
					view.updateStatus();
				});
			} catch (Exception ex) {
				view.ui(() -> view.setStatus("Move failed: " + ex.getMessage()));
			}
		}, "opencode-move").start();
	}

	private void refreshProviderSetupAsync() {
		new Thread(() -> {
			try {
				JsonObject providerStatus = view.service.providerStatus();
				boolean connected = providerStatus.getAsJsonArray("connected") != null
						&& !providerStatus.getAsJsonArray("connected").isEmpty();
				view.ui(() -> {
					view.providerConnected = connected;
					if (connected) {
						view.conversation.remove("setup-required");
						view.updateStatus();
					}
				});
			} catch (Exception ignored) {
				// Best-effort; the setup-required banner simply stays visible until the next check.
			}
		}, "opencode-provider-status").start();
	}

	private void compactSessionAsync() {
		if (view.busy) {
			view.setStatus("Stop the current response before compacting");
			return;
		}
		view.setStatus("Compacting session…");
		view.spinner.start();
		String session = view.service.getCurrentSessionId();
		new Thread(() -> {
			try {
				view.service.compactSession(session);
				JsonArray messages = view.service.getMessages(session);
				view.ui(() -> {
					view.spinner.stop();
					view.renderHistory(messages);
					view.updateStatus();
				});
			} catch (Exception ex) {
				view.ui(() -> {
					view.spinner.stop();
					view.setStatus("Compact failed: " + ex.getMessage());
				});
			}
		}, "opencode-compact").start();
	}
}
