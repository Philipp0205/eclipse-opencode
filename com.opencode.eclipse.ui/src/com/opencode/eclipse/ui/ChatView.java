package com.opencode.eclipse.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Button;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opencode.eclipse.core.OpenCodeService;
import com.opencode.eclipse.core.CommandInfo;
import com.opencode.eclipse.core.FilePartInput;

/**
 * Chat panel backed by a local {@code opencode serve} process.
 *
 * <p>Mirrors the VS Code extension's view: session + agent switchers, streaming
 * assistant text, tool-call lines, inline permission prompts, revert and abort.
 * Rendered with native SWT widgets (no Browser) — ponytail: {@link StyledText}
 * with light markdown, not an HTML renderer, until that proves insufficient.
 */
public final class ChatView extends ViewPart {
	public static final String ID = "com.opencode.eclipse.ui.chatView";
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
			new CommandInfo("help", "Show available Eclipse slash commands", "client", null, null, false, List.of()));

	private static final CommandInfo CONNECT_COMMAND =
			new CommandInfo("connect", "Connect an AI provider", "client", null, null, false, List.of());

	private final OpenCodeService service = new OpenCodeService();

	private Button sessionButton;
	private final List<JsonObject> allSessions = new ArrayList<>();
	private Combo agentCombo;
	private Button modelButton;
	private ConversationBrowser conversation;
	private TodoPanel todoPanel;
	private Composite attachedBar;
	private ChangedFilesBar changedFiles;
	private Composite queueBar;
	private Text input;
	private Label status;
	private Label activity;
	private SpinnerAnimator spinner;
	private SlashCommandPopup slashPopup;

	private final List<ModelChoice> modelChoices = new ArrayList<>();
	private List<CommandInfo> commands = List.of();
	private final ModelPicker modelPicker = new ModelPicker();
	private final ModelPicker sessionPicker = new ModelPicker();
	private final Diffs diffs = new Diffs();
	private final java.util.LinkedHashSet<String> manualAttachments = new java.util.LinkedHashSet<>();
	private final java.util.HashSet<String> excludedAttachments = new java.util.HashSet<>();
	private final List<String> promptHistory = new ArrayList<>();
	private final MessageQueue<QueuedPrompt> promptQueue = new MessageQueue<>();
	private int promptHistoryIndex;

	/** messageID -> role (user/assistant), learned from message.updated events. */
	private final Map<String, String> roles = new HashMap<>();
	private final Map<String, java.util.LinkedHashMap<String, JsonObject>> liveParts = new HashMap<>();
	private final Map<String, Double> messageCosts = new HashMap<>();
	private Image sendImage;
	private Image stopImage;
	private Image attachImage;
	private int mcpServers;
	private long contextUsed;
	private long contextLimit;
	private double sessionCost;
	private String workingFolder;
	private boolean providerConnected;
	private boolean attachAllOpen;
	private int interactionBlockers;
	private int turnGeneration;
	private String activeConversationActivity;
	private String activeAssistantMessage;
	private String runningSessionId;
	private volatile boolean busy;
	private org.eclipse.ui.IPartListener2 editorListener;
	private ModelChoice selectedModel;
	private record QueuedPrompt(String id, String text, String agent, ModelChoice model,
			List<OpenEditors.Attached> attachments) { }
	private record SessionChoice(String id, String title) { }

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(1, false));
		workingFolder = workspaceRoot();

		createToolbar(parent);
		createMessageArea(parent);
		todoPanel = new TodoPanel(parent);
		changedFiles = new ChangedFilesBar(parent, diffs);
		createQueueBar(parent);
		createAttachedBar(parent);
		createInput(parent);
		createSelectors(parent);

		Composite statusRow = new Composite(parent, SWT.NONE);
		statusRow.setLayout(new GridLayout(3, false));
		statusRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		activity = new Label(statusRow, SWT.NONE);
		spinner = new SpinnerAnimator(activity);
		status = new Label(statusRow, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Button contextButton = new Button(statusRow, SWT.PUSH | SWT.FLAT); contextButton.setText("Info");
		contextButton.setToolTipText("Show OpenCode session info"); contextButton.addListener(SWT.Selection, e -> showContext());
		setStatus("Starting opencode…");

		startServerAsync();
		installEditorListener();
		publishMonitorState();
	}

	private void installEditorListener() {
		editorListener = new org.eclipse.ui.IPartListener2() {
			@Override public void partActivated(org.eclipse.ui.IWorkbenchPartReference ref) { refreshIfEditor(ref); }
			@Override public void partOpened(org.eclipse.ui.IWorkbenchPartReference ref) { refreshIfEditor(ref); }
			@Override public void partClosed(org.eclipse.ui.IWorkbenchPartReference ref) { refreshIfEditor(ref); }
			private void refreshIfEditor(org.eclipse.ui.IWorkbenchPartReference ref) {
				if (ref instanceof org.eclipse.ui.IEditorReference && attachedBar != null && !attachedBar.isDisposed())
					attachedBar.getDisplay().asyncExec(() -> { if (!attachedBar.isDisposed()) refreshAttached(); });
			}
		};
		getSite().getPage().addPartListener(editorListener);
	}

	private void createQueueBar(Composite parent) {
		queueBar = new Composite(parent, SWT.BORDER);
		queueBar.setLayout(new GridLayout(2, false));
		queueBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		updateQueueBar();
	}

	private void updateQueueBar() {
		if (queueBar == null || queueBar.isDisposed()) return;
		for (var child : queueBar.getChildren()) child.dispose();
		boolean visible = !promptQueue.isEmpty();
		queueBar.setVisible(visible); ((GridData) queueBar.getLayoutData()).exclude = !visible;
		if (visible) {
			Label title = new Label(queueBar, SWT.NONE); title.setText(promptQueue.size() + " queued message(s)");
			new Label(queueBar, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			for (QueuedPrompt queued : promptQueue.snapshot()) {
				Label text = new Label(queueBar, SWT.NONE); text.setText(queued.text());
				text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
				Button remove = new Button(queueBar, SWT.PUSH | SWT.FLAT); remove.setText("×");
				remove.setToolTipText("Remove queued message");
				remove.addListener(SWT.Selection, e -> {
					promptQueue.remove(queued); conversation.remove(queued.id()); updateQueueBar();
				});
			}
		}
		queueBar.layout(true, true); queueBar.getParent().layout(true, true);
	}

	// ---- widgets ----------------------------------------------------------

	private void createToolbar(Composite parent) {
		Composite bar = new Composite(parent, SWT.NONE);
		bar.setLayout(new GridLayout(2, false));
		bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		new Label(bar, SWT.NONE).setText("Session:");
		sessionButton = new Button(bar, SWT.PUSH | SWT.FLAT);
		sessionButton.setAlignment(SWT.LEFT);
		sessionButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		sessionButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				openSessionPicker();
			}
		});
		createSessionMenu();

	}

	private void createSessionMenu() {
		Menu menu = new Menu(sessionButton);
		sessionButton.setMenu(menu);
		MenuItem rename = new MenuItem(menu, SWT.PUSH); rename.setText("Rename…");
		rename.addListener(SWT.Selection, e -> renameSession());
		MenuItem fork = new MenuItem(menu, SWT.PUSH); fork.setText("Fork session");
		fork.addListener(SWT.Selection, e -> sessionAction("fork"));
		MenuItem share = new MenuItem(menu, SWT.PUSH); share.setText("Share and copy URL");
		share.addListener(SWT.Selection, e -> sessionAction("share"));
		MenuItem unrevert = new MenuItem(menu, SWT.PUSH); unrevert.setText("Unrevert");
		unrevert.addListener(SWT.Selection, e -> sessionAction("unrevert"));
		new MenuItem(menu, SWT.SEPARATOR);
		MenuItem delete = new MenuItem(menu, SWT.PUSH); delete.setText("Delete…");
		delete.addListener(SWT.Selection, e -> deleteSession());
		menu.addListener(SWT.Show, e -> {
			boolean enabled = !busy && service.getCurrentSessionId() != null;
			for (MenuItem item : menu.getItems()) if ((item.getStyle() & SWT.SEPARATOR) == 0) item.setEnabled(enabled);
		});
	}

	private void renameSession() {
		org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
				getSite().getShell(), "Rename session", "Session title", sessionButton.getText(), null);
		if (dialog.open() != org.eclipse.jface.window.Window.OK) return;
		String id = service.getCurrentSessionId();
		new Thread(() -> {
			try { service.renameSession(id, dialog.getValue()); refreshSessionsAsync(); }
			catch (Exception ex) { ui(() -> setStatus("Rename failed: " + ex.getMessage())); }
		}, "opencode-rename").start();
	}

	private void deleteSession() {
		if (!MessageDialog.openConfirm(getSite().getShell(), "Delete session", "Delete this OpenCode session?")) return;
		String id = service.getCurrentSessionId();
		new Thread(() -> {
			try {
				service.deleteSession(id);
			} catch (Exception ex) { ui(() -> setStatus("Delete failed: " + ex.getMessage())); }
		}, "opencode-delete").start();
	}

	private void sessionAction(String action) {
		String id = service.getCurrentSessionId();
		new Thread(() -> {
			try {
				JsonObject session = switch (action) {
					case "fork" -> service.forkSession(id, null);
					case "share" -> service.shareSession(id);
					case "unrevert" -> service.unrevertSession(id);
					default -> throw new IllegalArgumentException(action);
				};
				if ("share".equals(action) && session.has("share")) {
					String url = str(session.getAsJsonObject("share"), "url");
					ui(() -> copyToClipboard(url));
				}
				if ("fork".equals(action)) service.switchSession(str(session, "id"));
				refreshSessionsAsync();
			} catch (Exception ex) { ui(() -> setStatus(action + " failed: " + ex.getMessage())); }
		}, "opencode-session-action").start();
	}

	private void refreshSessionsAsync() {
		try { JsonArray sessions = service.listSessions(); ui(() -> fillSessions(sessions)); }
		catch (Exception ex) { ui(() -> setStatus("Session refresh failed: " + ex.getMessage())); }
	}

	private void copyToClipboard(String text) {
		if (text == null) return;
		org.eclipse.swt.dnd.Clipboard clipboard = new org.eclipse.swt.dnd.Clipboard(getSite().getShell().getDisplay());
		try { clipboard.setContents(new Object[] { text }, new org.eclipse.swt.dnd.Transfer[] {
				org.eclipse.swt.dnd.TextTransfer.getInstance() }); } finally { clipboard.dispose(); }
	}

	/** Agent and model selection live below the prompt, matching Copilot's input layout. */
	private void createSelectors(Composite parent) {
		Composite bar = new Composite(parent, SWT.NONE);
		bar.setLayout(new GridLayout(4, false));
		bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		new Label(bar, SWT.NONE).setText("Agent:");
		agentCombo = new Combo(bar, SWT.READ_ONLY);
		agentCombo.setText("Loading agents…");
		GridData agentGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
		agentGd.widthHint = 110;
		agentCombo.setLayoutData(agentGd);

		new Label(bar, SWT.NONE).setText("Model:");
		modelButton = new Button(bar, SWT.PUSH | SWT.FLAT);
		modelButton.setText("Loading default model…");
		GridData modelGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
		modelGd.widthHint = 220;
		modelButton.setLayoutData(modelGd);
		modelButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				openModelPicker();
			}
		});
	}

	/** Row above the input showing files auto-attached to the next prompt. */
	private void createAttachedBar(Composite parent) {
		attachedBar = new Composite(parent, SWT.NONE);
		RowLayout rl = new RowLayout(SWT.HORIZONTAL);
		rl.center = true;
		rl.marginTop = 0;
		rl.marginBottom = 0;
		rl.wrap = true;
		attachedBar.setLayout(rl);
		attachedBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		attachedBar.setMenu(new Menu(attachedBar));
	}

	private void createMessageArea(Composite parent) {
		conversation = new ConversationBrowser(parent);
		conversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
	}

	private void createInput(Composite parent) {
		Composite row = new Composite(parent, SWT.NONE);
		row.setLayout(new GridLayout(3, false));
		row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		input = new Text(row, SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
		GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
		gd.heightHint = 56;
		input.setLayoutData(gd);
		input.addListener(SWT.KeyDown, e -> {
			if (slashPopup != null && slashPopup.handleKey(e)) {
				e.doit = false;
				return;
			}
			if (e.keyCode == SWT.CR && (e.stateMask & SWT.SHIFT) == 0) {
				e.doit = false;
				send();
			}
			if ((e.stateMask & SWT.ALT) != 0 && (e.keyCode == SWT.ARROW_UP || e.keyCode == SWT.ARROW_DOWN)) {
				navigatePromptHistory(e.keyCode == SWT.ARROW_UP ? -1 : 1); e.doit = false;
			}
		});
		slashPopup = new SlashCommandPopup(input, command -> {
			input.setText("/" + command.name() + " ");
			input.setSelection(input.getCharCount());
			input.setFocus();
		});
		input.addModifyListener(e -> {
			slashPopup.update(commands);
			int height = Math.max(56, Math.min(180, input.computeSize(Math.max(1, input.getSize().x), SWT.DEFAULT).y));
			if (gd.heightHint != height) { gd.heightHint = height; row.getParent().layout(true, true); }
		});

		org.eclipse.swt.widgets.Button sendBtn = new org.eclipse.swt.widgets.Button(row, SWT.PUSH | SWT.FLAT);
		sendImage = loadIcon(row.getDisplay(), "icons/chat/send.png");
		sendBtn.setImage(sendImage);
		sendBtn.setToolTipText("Send");
		sendBtn.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
			@Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent e) { e.result = "Send prompt"; }
		});
		sendBtn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				send();
			}
		});

		org.eclipse.swt.widgets.Button stopBtn = new org.eclipse.swt.widgets.Button(row, SWT.PUSH | SWT.FLAT);
		stopImage = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ELCL_STOP);
		stopBtn.setImage(stopImage);
		stopBtn.setToolTipText("Stop");
		stopBtn.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
			@Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent e) { e.result = "Stop response"; }
		});
		stopBtn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				abortAsync();
			}
		});
	}

	// ---- server startup ---------------------------------------------------

	private void startServerAsync() {
		new Thread(() -> {
			try {
				service.initialize(workspaceRoot());
				service.createSession();
				List<JsonObject> agents = service.getAgents();
				JsonArray sessions = service.listSessions();
				JsonObject providers = service.listProviders();
				JsonObject providerStatus = service.providerStatus();
				JsonObject mcp = service.getMcpStatus();
				List<CommandInfo> loadedCommands = service.listCommands();
				JsonArray pendingPermissions = service.listPendingPermissions();
				JsonArray pendingQuestions = service.listPendingQuestions();
				String configModel = service.getConfig().has("model")
						? service.getConfig().get("model").getAsString() : null;
				ui(() -> {
					mcpServers = connectedMcpServers(mcp);
					providerConnected = providerStatus.getAsJsonArray("connected") != null
							&& !providerStatus.getAsJsonArray("connected").isEmpty();
					List<CommandInfo> allCommands = new ArrayList<>(CLIENT_COMMANDS); allCommands.add(CONNECT_COMMAND);
					allCommands.addAll(loadedCommands);
					commands = List.copyOf(allCommands);
					fillAgents(agents);
					fillSessions(sessions);
					fillModels(providers, configModel);
					service.watchSessionEvents(event -> ui(() -> onSessionEvent(event)));
					refreshAttached();
					updateStatus();
					if (!providerConnected) {
						setStatus("OpenCode needs a provider · type /connect");
						conversation.putMessage("setup-required", "assistant",
								"**OpenCode is installed, but no AI provider is connected.**\n\nType `/connect` to sign in or add an API key.");
					}
					recoverInteractions(pendingPermissions, pendingQuestions);
					if (Boolean.getBoolean("opencode.wholeViewProbe") && getViewSite().getSecondaryId() == null) {
						runWholeViewProbe();
					}
				});
			} catch (Exception ex) {
				ui(() -> setStatus("Failed to start opencode: " + ex.getMessage()));
			}
		}, "opencode-startup").start();
	}

	/** Populate the model picker from config/providers; preselect the config model. */
	private void fillModels(JsonObject providers, String configModel) {
		modelChoices.clear(); modelChoices.addAll(ModelChoice.from(providers));
		selectedModel = modelChoices.stream().filter(choice -> choice.model().equals(configModel) && choice.variant() == null)
				.findFirst().orElse(modelChoices.isEmpty() ? null : modelChoices.get(0));
		selectModel(selectedModel);
	}

	private void openModelPicker() {
		modelPicker.toggle(modelButton, modelChoices, ModelChoice::label, "Search models", this::selectModel);
	}

	private void selectModel(ModelChoice selected) {
		selectedModel = selected;
		modelButton.setText(selected != null ? selected.label() : "");
		service.setModel(selected != null ? selected.model() : null);
		contextLimit = selected != null ? selected.contextLimit() : 0;
		updateStatus();
	}

	/** Rebuild the attached-resources chips from the currently open editors. */
	private void refreshAttached() {
		if (attachedBar == null || attachedBar.isDisposed()) {
			return;
		}
		for (var c : attachedBar.getChildren()) {
			c.dispose();
		}
		List<OpenEditors.Attached> open = OpenEditors.all();
		excludedAttachments.removeIf(path -> open.stream().noneMatch(item -> item.path().equals(path)));
		Button add = new Button(attachedBar, SWT.PUSH | SWT.FLAT);
		if (attachImage == null || attachImage.isDisposed()) attachImage = loadIcon(add.getDisplay(), "icons/chat/attach_context.png");
		add.setImage(attachImage); add.setToolTipText("Add Context…");
		add.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
			@Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent e) { e.result = "Add Context"; }
		});
		add.addListener(SWT.Selection, e -> addAttachment());
		Button allTabs = new Button(attachedBar, SWT.CHECK); allTabs.setText("All open tabs");
		allTabs.setSelection(attachAllOpen); allTabs.addListener(SWT.Selection, e -> {
			attachAllOpen = allTabs.getSelection(); refreshAttached();
		});
		java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
		AttachmentSelection.select(open, OpenEditors.Attached::active, attachAllOpen).stream()
				.map(OpenEditors.Attached::path).filter(path -> !excludedAttachments.contains(path)).forEach(paths::add);
		paths.addAll(manualAttachments);
		if (paths.isEmpty()) {
			Label none = new Label(attachedBar, SWT.NONE);
			none.setText("No open files attached");
			none.setForeground(attachedBar.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
		} else {
			for (String path : paths) {
				Button chip = new Button(attachedBar, SWT.PUSH | SWT.FLAT | SWT.BORDER);
				String name = java.nio.file.Path.of(path).getFileName().toString();
				boolean active = open.stream().anyMatch(item -> item.active() && item.path().equals(path));
				chip.setText((active ? "\u25CF " : "") + name + "  ×");
				chip.setToolTipText(path);
				chip.addListener(SWT.Selection, e -> {
					manualAttachments.remove(path);
					if (open.stream().anyMatch(item -> item.path().equals(path))) excludedAttachments.add(path);
					refreshAttached();
				});
			}
		}
		attachedBar.getParent().layout(true, true);
	}

	private void addAttachment() {
		AttachResourceDialog dialog = new AttachResourceDialog(getSite().getShell(),
				org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot());
		if (dialog.open() != org.eclipse.jface.window.Window.OK) return;
		for (Object result : dialog.getResult()) if (result instanceof org.eclipse.core.resources.IResource resource
				&& resource.getLocation() != null) {
			String path = resource.getLocation().toOSString();
			excludedAttachments.remove(path); manualAttachments.add(path);
		}
		refreshAttached();
	}

	private static String workspaceRoot() {
		var root = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
		String eclipseRoot = root.getLocation() != null ? root.getLocation().toOSString() : System.getProperty("user.dir");
		return WorkspaceRoot.resolve(System.getenv("ENV_SCM_WORKSPACE_ROOT"), eclipseRoot);
	}

	private void fillAgents(List<JsonObject> agents) {
		agentCombo.removeAll();
		int selected = -1;
		for (JsonObject a : agents) {
			agentCombo.add(a.get("name").getAsString());
			if ("build".equals(a.get("name").getAsString())) selected = agentCombo.getItemCount() - 1;
		}
		if (agentCombo.getItemCount() > 0) {
			agentCombo.select(selected >= 0 ? selected : 0);
		}
	}

	private void fillSessions(JsonArray sessions) {
		allSessions.clear();
		for (JsonElement element : sessions) allSessions.add(element.getAsJsonObject());
		String current = service.getCurrentSessionId();
		allSessions.stream().filter(s -> s.get("id").getAsString().equals(current)).findFirst()
				.ifPresent(s -> {
					sessionButton.setText(sessionLabel(s));
					setPartName(sessionLabel(s));
					workingFolder = sessionDirectory(s);
					updateStatus();
				});
	}

	private void openSessionPicker() {
		List<SessionChoice> sessions = allSessions.stream()
				.map(s -> new SessionChoice(s.get("id").getAsString(), title(s))).toList();
		sessionPicker.toggle(sessionButton, sessions, SessionChoice::title, "Search sessions",
				choice -> switchSession(choice.id()));
	}

	private static String title(JsonObject s) {
		String t = s.has("title") && !s.get("title").isJsonNull() ? s.get("title").getAsString() : null;
		return (t == null || t.isBlank()) ? "New Session" : t;
	}

	private static String sessionLabel(JsonObject session) {
		return title(session);
	}

	private String sessionDirectory(JsonObject session) {
		String directory = str(session, "directory");
		return directory != null && !directory.isBlank() ? directory : service.getCurrentSessionDirectory();
	}

	// ---- session switching ------------------------------------------------

	private void switchSession(String sid) {
		if (busy || !promptQueue.isEmpty()) {
			setStatus("Finish or remove queued messages before switching sessions");
			return;
		}
		new Thread(() -> {
			try {
				JsonObject selectedSession = service.switchSession(sid);
				JsonArray sessions = service.listSessions();
				JsonArray msgs = service.getMessages(sid);
				JsonArray todos = service.getSessionTodos(sid);
				JsonArray permissions = service.listPendingPermissions();
				JsonArray questions = service.listPendingQuestions();
				ui(() -> {
					workingFolder = sessionDirectory(selectedSession);
					fillSessions(sessions);
					renderHistory(msgs);
					todoPanel.setTodos(todos);
					recoverInteractions(permissions, questions);
				});
			} catch (Exception ex) {
				ui(() -> setStatus("Switch failed: " + ex.getMessage()));
			}
		}, "opencode-switch").start();
	}

	private void newSessionAsync() {
		if (busy || !promptQueue.isEmpty()) {
			setStatus("Finish or remove queued messages before creating a session");
			return;
		}
		new Thread(() -> {
			try {
				service.createSession();
				String sessionDirectory = service.getCurrentSessionDirectory();
				JsonArray sessions = service.listSessions();
				ui(() -> {
					workingFolder = sessionDirectory;
					clearMessages();
					fillSessions(sessions);
					setStatus("New session");
				});
			} catch (Exception ex) {
				ui(() -> setStatus("New session failed: " + ex.getMessage()));
			}
		}, "opencode-new").start();
	}

	/** Invoked by the declarative view-toolbar command. */
	public void startNewSession() {
		newSessionAsync();
	}

	private void renderHistory(JsonArray msgs) {
		roles.clear();
		liveParts.clear();
		contextUsed = 0;
		sessionCost = 0;
		messageCosts.clear();
		for (JsonElement element : msgs) {
			JsonObject info = element.getAsJsonObject().getAsJsonObject("info");
			if (info != null && "assistant".equals(str(info, "role"))) {
				if (info.has("cost") && !info.get("cost").isJsonNull()) {
					messageCosts.put(str(info, "id"), info.get("cost").getAsDouble());
				}
				JsonObject tokens = info.getAsJsonObject("tokens");
				if (tokens != null) contextUsed = Math.max(contextUsed,
						number(tokens, "input") + number(tokens, "output") + number(tokens.getAsJsonObject("cache"), "read"));
			}
		}
		sessionCost = messageCosts.values().stream().mapToDouble(Double::doubleValue).sum();
		conversation.setConversation(msgs);
		updateStatus();
	}

	// ---- sending / streaming ---------------------------------------------

	private void send() {
		String text = input.getText().trim();
		if (text.isEmpty() || !service.isReady()) {
			return;
		}
		input.setText("");
		if (promptHistory.isEmpty() || !promptHistory.get(promptHistory.size() - 1).equals(text)) promptHistory.add(text);
		promptHistoryIndex = promptHistory.size();
		slashPopup.close();
		SlashCommands.Invocation invocation = SlashCommands.parse(commands, text);
		if (invocation != null && "client".equals(invocation.command().source())) {
			handleClientCommand(invocation.command().name());
			return;
		}
		if (!providerConnected) {
			setStatus("Connect an AI provider first with /connect");
			return;
		}
		List<OpenEditors.Attached> attached = new ArrayList<>(AttachmentSelection
				.select(OpenEditors.all(), OpenEditors.Attached::active, attachAllOpen).stream()
				.filter(item -> !excludedAttachments.contains(item.path())).toList());
		for (String path : manualAttachments) if (attached.stream().noneMatch(item -> item.path().equals(path))) {
			attached.add(new OpenEditors.Attached(path, false, null, null, List.of()));
		}
		String agent = agentCombo.getSelectionIndex() >= 0 ? agentCombo.getText() : null;
		QueuedPrompt queued = new QueuedPrompt("local-user-" + System.nanoTime(), text, agent, selectedModel,
				List.copyOf(attached));
		if (busy) {
			promptQueue.add(queued);
			conversation.putMessage(queued.id(), "user", text + "\n\n*Queued*");
			updateQueueBar();
			return;
		}
		dispatch(queued);
	}

	private void dispatch(QueuedPrompt queued) {
		busy = true;
		publishMonitorState();
		startActivity("Thinking");
		conversation.putMessage(queued.id(), "user", queued.text());
		roles.clear(); liveParts.clear();
		diffs.reset(); changedFiles.reset();
		for (OpenEditors.Attached attachment : queued.attachments()) diffs.snapshotIfAbsent(attachment.path());
		String prompt = withAttachedContext(queued.text(), queued.attachments());
		SlashCommands.Invocation invocation = SlashCommands.parse(commands, queued.text());
		String sessionId = service.getCurrentSessionId();
		runningSessionId = sessionId;
		int turn = ++turnGeneration;
		activeConversationActivity = "thinking-" + turn;
		conversation.showActivity(activeConversationActivity);
		new Thread(() -> {
			try {
				if (invocation != null) {
					service.executeCommandStreaming(invocation.command().name(), invocation.arguments(),
							event -> ui(() -> onEvent(event)), sessionId, queued.agent(), queued.model().model(),
							queued.model().variant(), fileParts(queued.attachments()));
				} else {
					service.sendPromptStreaming(prompt, event -> ui(() -> onEvent(event)), sessionId, queued.agent(),
							queued.model().model(), queued.model().variant());
				}
			} catch (Exception ex) {
				ui(() -> {
					if (turn == turnGeneration) setStatus("Prompt failed: " + ex.getMessage());
				});
			} finally {
				ui(() -> {
					if (turn == turnGeneration) {
						busy = false;
						publishMonitorState();
						runningSessionId = null;
						stopActivity();
						drainQueue();
					}
				});
			}
		}, "opencode-prompt").start();
	}

	private void drainQueue() {
		if (busy || promptQueue.isEmpty()) return;
		QueuedPrompt next = promptQueue.poll(); updateQueueBar(); dispatch(next);
	}

	private void handleClientCommand(String command) {
		switch (command) {
			case "model", "models" -> openModelPicker();
			case "agents" -> { agentCombo.setFocus(); agentCombo.setListVisible(true); }
			case "sessions" -> openSessionPicker();
			case "new" -> newSessionAsync();
			case "compact" -> compactSessionAsync();
			case "move" -> moveSession();
			case "restart" -> restartOpenCode();
			case "mcps" -> new McpDialog(getSite().getShell(), service).open();
			case "help" -> conversation.putMessage("client-help-" + System.nanoTime(), "assistant",
					"**Eclipse commands:** `/models`, `/agents`, `/sessions`, `/new`, `/move`, `/restart`, `/mcps`, `/help`\n\n"
					+ "Project commands, MCP prompts, and skills are also available through `/`.");
			case "connect" -> {
				new ConnectProviderDialog(getSite().getShell(), service, this::refreshProviderSetupAsync).open();
			}
			default -> { }
		}
	}

	private void restartOpenCode() {
		if (busy || !promptQueue.isEmpty()) { setStatus("Finish current work before restarting OpenCode"); return; }
		String session = service.getCurrentSessionId(); setStatus("Restarting OpenCode…"); spinner.start();
		new Thread(() -> {
			try {
				service.dispose(); service.initialize(workspaceRoot());
				try { if (session != null) service.switchSession(session); else service.createSession(); }
				catch (Exception ignored) { service.createSession(); }
				JsonArray sessions = service.listSessions(); JsonArray messages = service.getMessages(service.getCurrentSessionId());
				List<JsonObject> agents = service.getAgents(); JsonObject providers = service.listProviders();
				JsonObject config = service.getConfig(); List<CommandInfo> loadedCommands = service.listCommands();
				ui(() -> {
					spinner.stop(); fillSessions(sessions); renderHistory(messages); fillAgents(agents);
					fillModels(providers, config.has("model") ? config.get("model").getAsString() : null);
					List<CommandInfo> allCommands = new ArrayList<>(CLIENT_COMMANDS); allCommands.add(CONNECT_COMMAND);
					allCommands.addAll(loadedCommands); commands = List.copyOf(allCommands);
					service.watchSessionEvents(event -> ui(() -> onSessionEvent(event)));
				});
			} catch (Exception ex) { ui(() -> { spinner.stop(); setStatus("Restart failed: " + ex.getMessage()); }); }
		}, "opencode-restart").start();
	}

	private void moveSession() {
		org.eclipse.swt.widgets.DirectoryDialog dialog = new org.eclipse.swt.widgets.DirectoryDialog(getSite().getShell());
		dialog.setText("Move OpenCode session"); dialog.setMessage("Select the destination directory");
		dialog.setFilterPath(service.getCurrentSessionDirectory());
		String destination = dialog.open(); if (destination == null) return;
		String session = service.getCurrentSessionId();
		new Thread(() -> {
			try { service.moveSession(session, destination); ui(() -> { workingFolder = destination; updateStatus(); }); }
			catch (Exception ex) { ui(() -> setStatus("Move failed: " + ex.getMessage())); }
		}, "opencode-move").start();
	}

	private void refreshProviderSetupAsync() {
		new Thread(() -> {
			try {
				JsonObject status = service.providerStatus();
				boolean connected = status.getAsJsonArray("connected") != null && !status.getAsJsonArray("connected").isEmpty();
				ui(() -> { providerConnected = connected; if (connected) { conversation.remove("setup-required"); updateStatus(); } });
			} catch (Exception ignored) { }
		}, "opencode-provider-status").start();
	}

	private void compactSessionAsync() {
		if (busy) { setStatus("Stop the current response before compacting"); return; }
		setStatus("Compacting session…"); spinner.start();
		String session = service.getCurrentSessionId();
		new Thread(() -> {
			try {
				service.compactSession(session);
				JsonArray messages = service.getMessages(session);
				ui(() -> { spinner.stop(); renderHistory(messages); updateStatus(); });
			} catch (Exception ex) { ui(() -> { spinner.stop(); setStatus("Compact failed: " + ex.getMessage()); }); }
		}, "opencode-compact").start();
	}

	/** Prefix the prompt with the attached file paths so the agent knows the working set. */
	private static String withAttachedContext(String text, List<OpenEditors.Attached> attached) {
		if (attached.isEmpty()) {
			return text;
		}
		StringBuilder sb = new StringBuilder("Attached files (currently open in the editor):\n");
		for (OpenEditors.Attached a : attached) {
			sb.append("- ").append(a.path());
			if (a.active()) {
				sb.append("  (active tab)");
			}
			sb.append('\n');
			if (a.selection() != null) sb.append("  Selected text:\n```\n").append(a.selection()).append("\n```\n");
			if (a.unsavedContent() != null) sb.append("  Unsaved editor content:\n```\n")
					.append(a.unsavedContent()).append("\n```\n");
			for (String problem : a.problems()) sb.append("  Eclipse problem: ").append(problem).append('\n');
		}
		sb.append('\n').append(text);
		return sb.toString();
	}

	private static List<FilePartInput> fileParts(List<OpenEditors.Attached> attachments) {
		return attachments.stream().map(attachment -> {
			java.nio.file.Path path = java.nio.file.Path.of(attachment.path());
			String mime;
			try { mime = java.nio.file.Files.isDirectory(path) ? "application/x-directory"
					: java.nio.file.Files.probeContentType(path); } catch (Exception ignored) { mime = null; }
			if (mime == null) mime = attachment.unsavedContent() != null ? "text/plain" : "application/octet-stream";
			String url = attachment.unsavedContent() == null ? path.toUri().toString()
					: "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(
							attachment.unsavedContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return new FilePartInput(mime, path.getFileName().toString(), url);
		}).toList();
	}

	private void onSessionEvent(com.opencode.eclipse.core.OpenCodeEvent event) {
		String deleted = "session.deleted".equals(event.type()) ? event.sessionID() : null;
		new Thread(() -> {
			try {
				if (deleted != null && deleted.equals(service.getCurrentSessionId())) {
					JsonArray sessions = service.listSessions();
					if (sessions.isEmpty()) service.createSession();
					else service.switchSession(str(sessions.get(0).getAsJsonObject(), "id"));
					String current = service.getCurrentSessionId();
					JsonArray updated = service.listSessions(); JsonArray messages = service.getMessages(current);
					JsonArray todos = service.getSessionTodos(current);
					ui(() -> { fillSessions(updated); renderHistory(messages); todoPanel.setTodos(todos); });
				} else refreshSessionsAsync();
			} catch (Exception ex) { ui(() -> setStatus("Session refresh failed: " + ex.getMessage())); }
		}, "opencode-session-refresh").start();
	}

	/** Called on the SWT thread. */
	private void onEvent(com.opencode.eclipse.core.OpenCodeEvent ev) {
		JsonObject raw = ev.raw();
		switch (ev.type()) {
		case "message.updated" -> {
			// Remember which messageIDs are assistant messages so parts render correctly.
			String role = Events.messageRole(raw);
			String id = Events.messageId(raw);
			if (id != null && role != null) {
				roles.put(id, role);
				if ("assistant".equals(role)) {
					removeConversationActivity();
					activeAssistantMessage = id;
					renderLiveMessage(id);
				}
			}
			if ("assistant".equals(role)) {
				updateContext(raw);
			}
		}
		case "message.part.updated" -> {
			JsonObject part = Events.part(raw);
			if (part == null) {
				return;
			}
			String mid = str(part, "messageID");
			String type = str(part, "type");
			JsonArray todos = Events.todos(part);
			if (todos != null) todoPanel.setTodos(todos);
			if ("tool".equals(type)) snapshotToolTarget(part);
			if (mid != null && ("text".equals(type) || "reasoning".equals(type) || "tool".equals(type)
					|| "subtask".equals(type) || "agent".equals(type))) {
				// Skip echoing the user's own text part (it already has a bubble).
				if ("user".equals(roles.get(mid))) {
					return;
				}
				String key = str(part, "id");
				if (key == null) key = toolKey(part);
				synchronized (liveParts) {
					liveParts.computeIfAbsent(mid, ignored -> new java.util.LinkedHashMap<>())
							.put(key, part.deepCopy());
				}
				renderLiveMessage(mid);
			}
		}
		case "session.error" -> {
			String msg = Events.errorMessage(raw);
			{
				removeConversationActivity();
				conversation.putMessage("error-" + System.nanoTime(), "assistant",
						"**Error:** " + (msg != null ? msg : "unknown error"));
			}
		}
		case "permission.asked" -> handlePermission(raw);
		case "question.asked" -> handleQuestion(raw);
		case "todo.updated" -> {
			JsonObject props = Events.props(raw);
			if (props != null) todoPanel.setTodos(props.getAsJsonArray("todos"));
		}
		case "file.edited" -> {
			String file = Events.editedFile(raw);
			if (file != null) {
				// Snapshot "before" if this is the first edit we see for the file,
				// then refresh in the workbench and open a compare view.
				diffs.snapshotIfAbsent(file);
				onFileEdited(file);
			}
		}
		case "session.idle", "session.status" -> {
			if (!ev.isIdle()) break;
			if (activeAssistantMessage != null) {
				renderLiveMessage(activeAssistantMessage, false);
				activeAssistantMessage = null;
			}
			removeConversationActivity();
			reviewSessionChanges(ev.sessionID());
		}
		default -> {
			// ignore others (session.idle handled by the streaming loop)
		}
		}
	}

	private void reviewSessionChanges(String sessionId) {
		new Thread(() -> {
			try {
				JsonArray changes = service.getDiff(sessionId);
				ui(() -> {
					for (JsonElement element : changes) {
						String path = str(element.getAsJsonObject(), "file");
						if (path != null) {
							java.nio.file.Path absolute = java.nio.file.Path.of(path);
							if (!absolute.isAbsolute()) absolute = java.nio.file.Path.of(service.getWorkspaceRoot()).resolve(absolute);
							changedFiles.add(absolute.normalize().toString());
						}
					}
					changedFiles.reviewPending();
				});
			} catch (Exception ex) {
				ui(changedFiles::reviewPending);
			}
		}, "opencode-review-diffs").start();
	}

	private void snapshotToolTarget(JsonObject part) {
		String tool = str(part, "tool");
		if (!"edit".equals(tool) && !"write".equals(tool) && !"apply_patch".equals(tool)) return;
		JsonObject state = part.getAsJsonObject("state");
		JsonObject input = state != null ? state.getAsJsonObject("input") : null;
		String path = input != null ? str(input, "filePath") : null;
		if (path == null && input != null) path = str(input, "path");
		if (path == null || path.isBlank()) return;
		java.nio.file.Path absolute = java.nio.file.Path.of(path);
		if (!absolute.isAbsolute()) absolute = java.nio.file.Path.of(service.getWorkspaceRoot()).resolve(absolute);
		diffs.snapshotIfAbsent(absolute.normalize().toString());
	}

	private void renderLiveMessage(String messageId) {
		renderLiveMessage(messageId, true);
	}

	private void renderLiveMessage(String messageId, boolean expandReasoning) {
		java.util.LinkedHashMap<String, JsonObject> parts;
		synchronized (liveParts) {
			parts = liveParts.get(messageId);
			if (parts == null || parts.isEmpty()) return;
			parts = new java.util.LinkedHashMap<>(parts);
		}
		JsonArray array = new JsonArray();
		parts.values().forEach(array::add);
		conversation.putMessageHtml(messageId,
				ConversationHtml.message(messageId, roles.getOrDefault(messageId, "assistant"), array,
						expandReasoning));
	}

	/** Refresh the edited file in the workspace and open a before/after compare. */
	private void onFileEdited(String absolutePath) {
		refreshWorkspaceFile(absolutePath);
		changedFiles.add(absolutePath);
	}

	private void refreshWorkspaceFile(String absolutePath) {
		try {
			var wsRoot = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
			var files = wsRoot.findFilesForLocationURI(new java.io.File(absolutePath).toURI());
			for (var f : files) {
				f.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, null);
			}
		} catch (Exception ignored) {
			// best effort; the compare still shows on-disk content
		}
	}

	// ---- permissions / revert / abort ------------------------------------

	private void handlePermission(JsonObject event) {
		JsonObject p = Events.props(event);
		if (p == null) {
			return;
		}
		String sid = str(p, "sessionID");
		String pid = str(p, "id");
		String permission = str(p, "permission");
		JsonArray patterns = p.getAsJsonArray("patterns");
		if (sid == null || pid == null) {
			return;
		}
		// once / always / reject via a 3-button dialog (Yes / No / Cancel).
		interactionBlockers++; publishMonitorState();
		MessageDialog d = new MessageDialog(getSite().getShell(), "OpenCode permission", null,
				("Allow " + (permission != null ? permission : "this action") + "?"
						+ (patterns != null && !patterns.isEmpty() ? "\n\n" + patterns : "")),
				MessageDialog.QUESTION,
				new String[] { "Allow once", "Always", "Reject" }, 0);
		int choice = d.open();
		String response = switch (choice) {
			case 0 -> "once";
			case 1 -> "always";
			default -> "reject";
		};
		new Thread(() -> {
			try {
				service.respondToPermission(pid, response);
			} catch (Exception ex) {
				ui(() -> setStatus("Permission failed: " + ex.getMessage()));
			} finally { ui(() -> { interactionBlockers--; publishMonitorState(); }); }
		}, "opencode-perm").start();
	}

	private void handleQuestion(JsonObject event) {
		JsonObject props = Events.props(event);
		if (props == null) return;
		String requestId = str(props, "id");
		JsonArray questions = props.getAsJsonArray("questions");
		if (requestId == null || questions == null) return;
		interactionBlockers++; publishMonitorState();
		QuestionDialog dialog = new QuestionDialog(getSite().getShell(), questions);
		boolean accepted = dialog.open() == org.eclipse.jface.window.Window.OK;
		new Thread(() -> {
			try {
				if (accepted) service.replyQuestion(requestId, dialog.answers());
				else service.rejectQuestion(requestId);
			} catch (Exception ex) { ui(() -> setStatus("Question response failed: " + ex.getMessage())); }
			finally { ui(() -> { interactionBlockers--; publishMonitorState(); }); }
		}, "opencode-question").start();
	}

	private void recoverInteractions(JsonArray permissions, JsonArray questions) {
		String session = service.getCurrentSessionId();
		for (JsonElement element : permissions) {
			JsonObject request = element.getAsJsonObject();
			if (session.equals(str(request, "sessionID"))) {
				JsonObject event = new JsonObject(); event.add("properties", request); handlePermission(event); break;
			}
		}
		for (JsonElement element : questions) {
			JsonObject request = element.getAsJsonObject();
			if (session.equals(str(request, "sessionID"))) {
				JsonObject event = new JsonObject(); event.add("properties", request); handleQuestion(event); break;
			}
		}
	}

	private void abortAsync() {
		int abortedTurn = ++turnGeneration;
		spinner.stop();
		removeConversationActivity();
		setStatus("Stopping · " + metrics());
		new Thread(() -> {
			try {
				service.abortSession(runningSessionId);
			} catch (Exception ex) {
				ui(() -> setStatus("Abort failed: " + ex.getMessage()));
			} finally {
				ui(() -> {
					if (turnGeneration == abortedTurn) {
						busy = false;
						runningSessionId = null;
						setStatus("Aborted · " + metrics());
						input.setFocus();
						drainQueue();
					}
				});
			}
		}, "opencode-abort").start();
	}

	private void clearMessages() {
		roles.clear();
		liveParts.clear();
		conversation.clear();
		todoPanel.setTodos(null);
	}

	// ---- helpers ----------------------------------------------------------

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static String toolKey(JsonObject part) {
		String id = str(part, "id");
		if (id == null) {
			id = str(part, "callID");
		}
		return id != null ? id : str(part, "messageID") + ":" + str(part, "tool");
	}

	private static String toolLabel(JsonObject part) {
		String tool = str(part, "tool");
		JsonObject state = part.getAsJsonObject("state");
		JsonObject input = state != null ? state.getAsJsonObject("input") : null;
		String path = input != null ? str(input, "filePath") : null;
		if (path == null && input != null) {
			path = str(input, "path");
		}
		return "[tool] " + (tool != null ? tool : "?") + (path != null ? "  " + path : "");
	}

	private static Image loadIcon(Display display, String path) {
		var stream = ChatView.class.getClassLoader().getResourceAsStream(path);
		if (stream == null) {
			return null;
		}
		try (stream) {
			return new Image(display, stream);
		} catch (java.io.IOException e) {
			return null;
		}
	}

	private void startActivity(String text) {
		setStatus(text);
		spinner.start();
	}

	private void stopActivity() {
		spinner.stop();
		removeConversationActivity();
		updateStatus();
	}

	private void removeConversationActivity() {
		if (activeConversationActivity != null) {
			conversation.remove(activeConversationActivity);
			activeConversationActivity = null;
		}
	}

	private static int connectedMcpServers(JsonObject mcp) {
		int count = 0;
		for (var entry : mcp.entrySet()) {
			JsonObject value = entry.getValue().getAsJsonObject();
			if ("connected".equals(str(value, "status"))) {
				count++;
			}
		}
		return count;
	}

	private void updateContext(JsonObject event) {
		JsonObject info = Events.props(event).getAsJsonObject("info");
		JsonObject tokens = info != null ? info.getAsJsonObject("tokens") : null;
		if (info != null && info.has("cost") && !info.get("cost").isJsonNull()) {
			messageCosts.put(str(info, "id"), info.get("cost").getAsDouble());
			sessionCost = messageCosts.values().stream().mapToDouble(Double::doubleValue).sum();
		}
		if (tokens == null) {
			return;
		}
		long used = number(tokens, "input") + number(tokens, "output");
		JsonObject cache = tokens.getAsJsonObject("cache");
		used += number(cache, "read");
		if (used > 0) {
			contextUsed = used;
			ui(this::updateStatus);
		}
	}

	private static long number(JsonObject object, String key) {
		return object != null && object.has(key) ? object.get(key).getAsLong() : 0;
	}

	private String metrics() {
		String folder = workingFolder != null ? java.nio.file.Path.of(workingFolder).getFileName().toString() : "workspace";
		String context = contextLimit > 0 ? Math.round(contextUsed * 100.0 / contextLimit) + "% context" : "context —";
		return String.format(java.util.Locale.ROOT, "$%.2f · %s · %s", sessionCost, context, folder);
	}

	private void updateStatus() {
		setStatus((busy ? "Thinking · " : "Ready · ") + metrics());
		publishMonitorState();
	}

	private void publishMonitorState() {
		if (sessionButton == null || sessionButton.isDisposed()) return;
		ChatViewRegistry.update(this, sessionButton.getText().isBlank() ? "New Session" : sessionButton.getText(),
				ChatViewRegistry.status(busy, interactionBlockers));
	}

	private void navigatePromptHistory(int direction) {
		if (promptHistory.isEmpty()) return;
		promptHistoryIndex = Math.max(0, Math.min(promptHistory.size(), promptHistoryIndex + direction));
		input.setText(promptHistoryIndex == promptHistory.size() ? "" : promptHistory.get(promptHistoryIndex));
		input.setSelection(input.getCharCount());
	}

	private void showContext() {
		String details = "Used tokens: " + contextUsed + "\nContext limit: " + contextLimit + "\nUsage: "
				+ (contextLimit > 0 ? Math.round(contextUsed * 100.0 / contextLimit) + "%" : "unknown")
				+ String.format(java.util.Locale.ROOT, "\nCost: $%.2f", sessionCost)
				+ "\nWorking folder: " + workingFolder
				+ "\nSession ID: " + service.getCurrentSessionId()
				+ "\nConnected MCP servers: " + mcpServers;
		new InfoDialog(getSite().getShell(), details).open();
	}

	/**
	 * Opt-in runtime probe for the complete view. It drives the same widget/service
	 * methods as user actions and validates the Browser DOM after each stage.
	 */
	private void runWholeViewProbe() {
		probe("build".equals(agentCombo.getText()), "build agent selected");
		probe(modelButton.getText() != null && !modelButton.getText().isBlank(), "model selected");
		probe(status.getText().contains("$") && status.getText().contains(java.nio.file.Path.of(workingFolder).getFileName().toString()),
				"status cost and folder visible");
		probe(status.getText().contains("context"), "status context percentage visible");
		probe(OpenSettingsHandler.configPath().endsWith(java.nio.file.Path.of("opencode", "opencode.json")),
				"settings path resolved");
		try {
			OpenSettingsHandler.open();
			var editor = getSite().getPage().getActiveEditor();
			probe(editor != null && OpenSettingsHandler.EDITOR_ID.equals(editor.getSite().getId()),
					"settings opened in Eclipse text editor");
		} catch (Exception e) { throw new AssertionError(e); }
		probeMultipleViews(() -> probeTodoTool(() -> probeInputAndReply("WHOLE_VIEW_PROBE", this::probeFileEdit)));
	}

	private void probeMultipleViews(Runnable next) {
		try {
			var page = getSite().getPage();
			var secondary = page.showView(ID, "whole-view-secondary", org.eclipse.ui.IWorkbenchPage.VIEW_CREATE);
			page.showView(SessionMonitorView.ID, null, org.eclipse.ui.IWorkbenchPage.VIEW_CREATE);
			probeEventually("multiple chat views", 30_000, () -> ChatViewRegistry.snapshot().size() >= 2, () -> {
				page.activate(secondary); probe(page.getActivePart() == secondary, "monitor target activates");
				page.hideView(secondary); next.run();
			});
		} catch (Exception e) { throw new AssertionError(e); }
	}

	private void probeTodoTool(Runnable next) {
		ModelChoice originalModel = selectedModel;
		ModelChoice toolModel = modelChoices.stream()
				.filter(choice -> choice.variant() == null && choice.model().endsWith("/claude-sonnet-4.6"))
				.findFirst().orElseThrow(() -> new AssertionError("Whole-view probe needs claude-sonnet-4.6"));
		selectModel(toolModel);
		input.setText("Use the todowrite tool now to create exactly two todos: "
				+ "Whole-view first task in_progress high, Whole-view second task pending medium. "
				+ "Do not use another tool or reply until todowrite succeeds.");
		send();
		probeEventually("real todowrite tool", 120_000,
				() -> !busy && todoPanel.getChildren().length >= 4
						&& java.util.Arrays.stream(todoPanel.getChildren()).filter(Label.class::isInstance)
								.map(Label.class::cast).anyMatch(label -> label.getText().contains("Whole-view first task")),
				() -> { selectModel(originalModel); next.run(); });
	}

	private void probeFileEdit() {
		java.nio.file.Path file = java.nio.file.Path.of(service.getWorkspaceRoot(), "whole_view_probe.txt");
		try { java.nio.file.Files.writeString(file, "before\n"); } catch (Exception e) { throw new AssertionError(e); }
		input.setText("Edit whole_view_probe.txt so it contains exactly after");
		send();
		probeEventually("file edit", 240_000,
				() -> java.nio.file.Files.exists(file) && readProbeFile(file).contains("after"),
				() -> probeEventually("file edit completes", 120_000, () -> !busy, this::probeAbortAndContinue));
	}

	private static String readProbeFile(java.nio.file.Path file) {
		try { return java.nio.file.Files.readString(file); } catch (Exception e) { return ""; }
	}

	private void probeInputAndReply(String marker, Runnable next) {
		input.setText("Reply with exactly " + marker);
		send();
		probeEventually("immediate user card", 5_000,
				() -> browserTextContains("Reply with exactly " + marker),
				() -> probeEventually("assistant reply", 120_000,
						() -> !busy && browserTextContains(marker), next));
	}

	private void probeAbortAndContinue() {
		input.setText("Count slowly from 1 to 1000");
		send();
		input.setText("Reply with exactly WHOLE_VIEW_QUEUED");
		send();
		probe(promptQueue.size() == 1, "message queued while busy");
		probe(browserTextContains("Queued"), "queued card visible");
		conversation.getDisplay().timerExec(1500, () -> {
			abortAsync();
			probeEventually("abort completes", 15_000, () -> !busy, () -> {
				probeEventually("queued response", 120_000,
						() -> !busy && browserTextContains("WHOLE_VIEW_QUEUED"),
						() -> probeInputAndReply("WHOLE_VIEW_CONTINUED",
								() -> System.out.println("[OpenCodeProbe] WHOLE VIEW OK")));
			});
		});
	}

	private boolean browserTextContains(String text) {
		Object value = conversation.evaluate(
				"return document.getElementById('conversation').innerText;");
		return value instanceof String string && string.contains(text);
	}

	private void probeEventually(String name, long timeoutMs, java.util.function.BooleanSupplier condition,
			Runnable success) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		Runnable check = new Runnable() {
			@Override
			public void run() {
				if (condition.getAsBoolean()) {
					System.out.println("[OpenCodeProbe] PASS " + name);
					success.run();
				} else if (System.currentTimeMillis() >= deadline) {
					System.err.println("[OpenCodeProbe] FAIL " + name);
				} else {
					conversation.getDisplay().timerExec(250, this);
				}
			}
		};
		check.run();
	}

	private static void probe(boolean condition, String name) {
		if (!condition) throw new AssertionError("Whole-view probe failed: " + name);
		System.out.println("[OpenCodeProbe] PASS " + name);
	}

	private void setStatus(String s) {
		if (status != null && !status.isDisposed()) {
			status.setText(s);
		}
	}

	private void ui(Runnable r) {
		Display d = Display.getDefault();
		if (!d.isDisposed()) {
			d.asyncExec(() -> {
				if (!input.isDisposed()) {
					r.run();
				}
			});
		}
	}

	@Override
	public void setFocus() {
		if (input != null && !input.isDisposed()) {
			input.setFocus();
		}
	}

	@Override
	public void dispose() {
		ChatViewRegistry.remove(this);
		if (editorListener != null) getSite().getPage().removePartListener(editorListener);
		if (slashPopup != null) slashPopup.close();
		modelPicker.close();
		sessionPicker.close();
		service.dispose();
		if (spinner != null) spinner.stop();
		if (sendImage != null && !sendImage.isDisposed()) sendImage.dispose();
		if (attachImage != null && !attachImage.isDisposed()) attachImage.dispose();
		super.dispose();
	}
}
