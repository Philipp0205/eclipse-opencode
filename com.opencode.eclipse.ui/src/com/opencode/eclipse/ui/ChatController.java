package com.opencode.eclipse.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opencode.eclipse.core.OpenCodeEvent;

/**
 * Owns one chat turn's lifecycle: building and sending a prompt, streaming the SSE response,
 * dispatching each event to the right piece of view state, and handling permission/question
 * prompts and child-session bookkeeping along the way.
 *
 * <p>Split out of {@link ChatView} so all turn/streaming orchestration lives in one place,
 * separate from widget construction. Like {@link ChatViewProbe} and {@link CommandRouter}, this
 * class references its owning view via a constructor-injected reference and several
 * package-private {@code ChatView} members, rather than a full SWT-free callback interface —
 * a true {@code ChatViewCallbacks} abstraction is a larger follow-up, not attempted here given
 * how much of this logic (turn generation, live-part accumulation, child-session tracking)
 * is inherently about deciding *what* to render, which still needs to reach real widgets.
 */
final class ChatController {
	private static volatile boolean dashboardRefreshScheduled;

	private final ChatView view;

	ChatController(ChatView view) {
		this.view = view;
	}

	void send() {
		String text = view.input.getText().trim();
		if (text.isEmpty() || !view.service.isReady()) {
			return;
		}
		view.input.setText("");
		if (view.promptHistory.isEmpty() || !view.promptHistory.get(view.promptHistory.size() - 1).equals(text))
			view.promptHistory.add(text);
		view.promptHistoryIndex = view.promptHistory.size();
		view.slashPopup.close();
		SlashCommands.Invocation invocation = SlashCommands.parse(view.commands, text);
		if (invocation != null && "client".equals(invocation.command().source())) {
			view.commandRouter.handle(invocation.command().name(), invocation.arguments());
			return;
		}
		if (!view.providerConnected) {
			view.setStatus("Connect an AI provider first with /connect");
			return;
		}
		List<OpenEditors.Attached> attached = new ArrayList<>(AttachmentSelection
				.select(OpenEditors.all(), OpenEditors.Attached::active, view.attachAllOpen).stream()
				.filter(item -> !view.excludedAttachments.contains(item.path())).toList());
		for (String path : view.manualAttachments) if (attached.stream().noneMatch(item -> item.path().equals(path))) {
			attached.add(new OpenEditors.Attached(path, false, null, null, List.of()));
		}
		String agent = view.agentCombo.getSelectionIndex() >= 0 ? view.selectedAgentName() : null;
		ChatView.QueuedPrompt queued = new ChatView.QueuedPrompt("local-user-" + System.nanoTime(), text, agent,
				view.selectedModel, List.copyOf(attached));
		if (view.busy) {
			view.promptQueue.add(queued);
			view.conversation.putMessage(queued.id(), "user", text + "\n\n*Queued*");
			view.updateQueueBar();
			return;
		}
		dispatch(queued);
	}

	private void dispatch(ChatView.QueuedPrompt queued) {
		view.busy = true;
		view.publishMonitorState();
		view.startActivity("Thinking");
		view.conversation.putMessage(queued.id(), "user", queued.text());
		view.roles.clear();
		view.liveParts.clear();
		view.diffs.reset();
		view.changedFiles.reset();
		for (OpenEditors.Attached attachment : queued.attachments()) view.diffs.snapshotIfAbsent(attachment.path());
		String prompt = PromptBuilder.withAttachedContext(queued.text(), queued.attachments());
		SlashCommands.Invocation invocation = SlashCommands.parse(view.commands, queued.text());
		String sessionId = view.service.getCurrentSessionId();
		view.runningSessionId = sessionId;
		int turn = ++view.turnGeneration;
		view.activeConversationActivity = "thinking-" + turn;
		view.conversation.showActivity(view.activeConversationActivity);
		new Thread(() -> {
			try {
				if (invocation != null) {
					view.service.executeCommandStreaming(invocation.command().name(), invocation.arguments(),
							event -> view.ui(() -> onEvent(event)), sessionId, queued.agent(), queued.model().model(),
							queued.model().variant(), PromptBuilder.fileParts(queued.attachments()));
				} else {
					view.service.sendPromptStreaming(prompt, event -> view.ui(() -> onEvent(event)), sessionId,
							queued.agent(), queued.model().model(), queued.model().variant());
				}
			} catch (Exception ex) {
				view.ui(() -> {
					if (turn == view.turnGeneration) view.setStatus("Prompt failed: " + ex.getMessage());
				});
			} finally {
				view.ui(() -> {
					if (turn == view.turnGeneration) {
						view.busy = false;
						view.publishMonitorState();
						view.runningSessionId = null;
						view.stopActivity();
						drainQueue();
					}
				});
				if (turn == view.turnGeneration) {
					// The CLI may auto-title the session after the first reply; refresh
					// so the session button/part name/monitor pick up the new title.
					view.refreshSessionsAsync();
				}
			}
		}, "opencode-prompt").start();
	}

	void drainQueue() {
		if (view.busy || view.promptQueue.isEmpty()) return;
		ChatView.QueuedPrompt next = view.promptQueue.poll();
		view.updateQueueBar();
		dispatch(next);
	}

	void onSessionEvent(OpenCodeEvent event) {
		String deleted = "session.deleted".equals(event.type()) ? event.sessionID() : null;
		new Thread(() -> {
			try {
				if (deleted != null && deleted.equals(view.service.getCurrentSessionId()) && !view.deleting) {
					JsonArray sessions = view.service.listSessions();
					if (sessions.isEmpty()) view.service.createSession();
					else view.service.switchSession(ChatView.str(sessions.get(0).getAsJsonObject(), "id"));
					view.sessionRestore.persist(view.service.getCurrentSessionId(), view.service.getCurrentSessionDirectory());
					String current = view.service.getCurrentSessionId();
					JsonArray updated = view.service.listSessions();
					JsonArray messages = view.service.getMessages(current);
					view.ui(() -> { view.fillSessions(updated); view.renderHistory(messages); });
				} else view.refreshSessionsAsync();
			} catch (Exception ex) {
				view.ui(() -> view.setStatus("Session refresh failed: " + ex.getMessage()));
			}
		}, "opencode-session-refresh").start();
	}

	/** Called on the SWT thread. */
	private void onEvent(OpenCodeEvent ev) {
		JsonObject raw = ev.raw();
		switch (ev.type()) {
		case "message.updated" -> {
			// Remember which messageIDs are assistant messages so parts render correctly.
			String role = Events.messageRole(raw);
			String id = Events.messageId(raw);
			if (id != null && role != null) {
				view.roles.put(id, role);
				if ("assistant".equals(role)) {
					view.removeConversationActivity();
					view.activeAssistantMessage = id;
					renderLiveMessage(id);
				}
			}
			if ("assistant".equals(role)) {
				view.updateContext(raw);
			}
		}
		case "message.part.updated" -> {
			JsonObject part = Events.part(raw);
			if (part == null) {
				return;
			}
			String mid = ChatView.str(part, "messageID");
			String type = ChatView.str(part, "type");
			if ("tool".equals(type)) snapshotToolTarget(part);
			if ("subtask".equals(type) || ("tool".equals(type) && "task".equals(ChatView.str(part, "tool")))) {
				trackDelegatedChild(part);
			}
			if (mid != null && ("text".equals(type) || "reasoning".equals(type) || "tool".equals(type)
					|| "subtask".equals(type) || "agent".equals(type))) {
				// Skip echoing the user's own text part (it already has a bubble).
				if ("user".equals(view.roles.get(mid))) {
					return;
				}
				String key = ChatView.str(part, "id");
				if (key == null) key = ChatView.toolKey(part);
				synchronized (view.liveParts) {
					view.liveParts.computeIfAbsent(mid, ignored -> new LinkedHashMap<>()).put(key, part.deepCopy());
				}
				renderLiveMessage(mid);
			}
		}
		case "session.error" -> {
			String msg = Events.errorMessage(raw);
			view.removeConversationActivity();
			view.setStatus("OpenCode error · double-click for details");
			view.status.setToolTipText(msg != null ? msg : "OpenCode reported an unknown error");
			view.conversation.putMessage("error-" + System.nanoTime(), "assistant",
					"**Error:** " + (msg != null ? msg : "unknown error"));
		}
		case "permission.asked" -> handlePermission(raw);
		case "question.asked" -> handleQuestion(raw);
		case "file.edited" -> {
			String file = Events.editedFile(raw);
			if (file != null) {
				// Snapshot "before" if this is the first edit we see for the file,
				// then refresh in the workbench and open a compare view.
				view.diffs.snapshotIfAbsent(file);
				onFileEdited(file);
			}
		}
		case "session.idle", "session.status" -> {
			String eventSessionId = ev.sessionID();
			// A delegated child (subagent) reaching idle should disappear from the
			// sessions view immediately rather than waiting for the parent turn to end.
			if (eventSessionId != null && ChildSessionTracker.isTrackedBy(view, eventSessionId)) {
				if (ev.isIdle() && ChildSessionTracker.removeIdle(eventSessionId)) scheduleDashboardRefresh();
				break;
			}
			if (view.runningSessionId == null || !view.runningSessionId.equals(eventSessionId)) break;
			if (!ev.isIdle()) break;
			String completedRootSessionId = eventSessionId;
			if (view.activeAssistantMessage != null) {
				renderLiveMessage(view.activeAssistantMessage, false);
				view.activeAssistantMessage = null;
			}
			view.removeConversationActivity();
			reviewSessionChanges(completedRootSessionId);
			reconcileChildSessions(completedRootSessionId);
		}
		default -> {
			// ignore others (session.idle handled by the streaming loop)
		}
		}
	}

	/**
	 * A task-tool / subtask part on the current message describes a delegated child
	 * session (built-in opencode subagent or a custom "omo"-style agent defined under
	 * ~/.config/opencode/agent — both use the same task tool call shape). Track its
	 * session id, resolved agent name, and status so the sessions view can show it,
	 * and drop it the moment it reaches a terminal status so finished subagents don't
	 * linger there after the orchestrator itself is done.
	 */
	private void trackDelegatedChild(JsonObject part) {
		if (ChildSessionTracker.track(view, part, view.service.getCurrentSessionId(), view.workingFolder)) scheduleDashboardRefresh();
	}

	/** Coalesce dashboard refreshes so a burst of subagent tool-call events (which can
	 * arrive many times per second) does not flood the SWT UI thread with table
	 * rebuilds — that flooding is what made rename and other view actions feel like
	 * they stalled while subagents were active. */
	private static void scheduleDashboardRefresh() {
		if (dashboardRefreshScheduled) return;
		dashboardRefreshScheduled = true;
		Display.getDefault().timerExec(300, () -> {
			dashboardRefreshScheduled = false;
			SessionMonitorView.refreshAll();
			SessionsExplorerView.refreshAll();
		});
	}

	/** Final safety net: reconcile once against the server after the root turn ends,
	 * in case a child's own terminal SSE event was missed (e.g. it completed after the
	 * parent stream already closed). */
	private void reconcileChildSessions(String rootSessionId) {
		if (rootSessionId == null) return;
		new Thread(() -> {
			try {
				JsonArray descendants = view.service.getSessionDescendants(rootSessionId);
				Set<String> stillActive = new HashSet<>();
				for (JsonElement element : descendants) {
					if (!element.isJsonObject()) continue;
					JsonObject descendant = element.getAsJsonObject();
					String id = ChatView.str(descendant, "id");
					if (id != null && !ChildSessionTracker.isTerminalChildStatus(ChildSessionTracker.descendantStatus(descendant)))
						stillActive.add(id);
				}
				view.ui(() -> {
					ChildSessionTracker.retainOnly(view, stillActive);
					SessionMonitorView.refreshAll();
					SessionsExplorerView.refreshAll();
				});
			} catch (Exception ignored) {
				// Best-effort cleanup; SSE-driven removal remains authoritative on failure.
			}
		}, "opencode-child-reconcile").start();
	}

	private void reviewSessionChanges(String sessionId) {
		if (view.runningSessionId == null || !view.runningSessionId.equals(sessionId)) return;
		new Thread(() -> {
			try {
				JsonArray changes = view.service.getDiff(sessionId);
				view.ui(() -> {
					for (JsonElement element : changes) {
						String path = ChatView.str(element.getAsJsonObject(), "file");
						if (path != null) {
							Path absolute = Path.of(path);
							if (!absolute.isAbsolute()) absolute = Path.of(view.service.getWorkspaceRoot()).resolve(absolute);
							view.changedFiles.add(absolute.normalize().toString());
						}
					}
					view.changedFiles.reviewPending();
				});
			} catch (Exception ex) {
				view.ui(view.changedFiles::reviewPending);
			}
		}, "opencode-review-diffs").start();
	}

	private void snapshotToolTarget(JsonObject part) {
		String tool = ChatView.str(part, "tool");
		if (!"edit".equals(tool) && !"write".equals(tool) && !"apply_patch".equals(tool)) return;
		JsonObject state = part.getAsJsonObject("state");
		JsonObject toolInput = state != null ? state.getAsJsonObject("input") : null;
		String path = toolInput != null ? ChatView.str(toolInput, "filePath") : null;
		if (path == null && toolInput != null) path = ChatView.str(toolInput, "path");
		if (path == null || path.isBlank()) return;
		Path absolute = Path.of(path);
		if (!absolute.isAbsolute()) absolute = Path.of(view.service.getWorkspaceRoot()).resolve(absolute);
		view.diffs.snapshotIfAbsent(absolute.normalize().toString());
	}

	private void renderLiveMessage(String messageId) {
		renderLiveMessage(messageId, true);
	}

	private void renderLiveMessage(String messageId, boolean expandReasoning) {
		LinkedHashMap<String, JsonObject> parts;
		synchronized (view.liveParts) {
			parts = view.liveParts.get(messageId);
			if (parts == null || parts.isEmpty()) return;
			parts = new LinkedHashMap<>(parts);
		}
		JsonArray array = new JsonArray();
		parts.values().forEach(array::add);
		view.conversation.putMessageHtml(messageId,
				ConversationHtml.message(messageId, view.roles.getOrDefault(messageId, "assistant"), array,
						expandReasoning));
	}

	/** Refresh the edited file in the workspace and open a before/after compare. */
	private void onFileEdited(String absolutePath) {
		refreshWorkspaceFile(absolutePath);
		view.changedFiles.add(absolutePath);
	}

	private void refreshWorkspaceFile(String absolutePath) {
		try {
			var wsRoot = ResourcesPlugin.getWorkspace().getRoot();
			var files = wsRoot.findFilesForLocationURI(new File(absolutePath).toURI());
			for (var f : files) {
				f.refreshLocal(IResource.DEPTH_ZERO, null);
			}
		} catch (Exception ignored) {
			// best effort; the compare still shows on-disk content
		}
	}

	// ---- permissions / questions -------------------------------------------

	void handlePermission(JsonObject event) {
		JsonObject p = Events.props(event);
		if (p == null) {
			return;
		}
		String sid = ChatView.str(p, "sessionID");
		String pid = ChatView.str(p, "id");
		String permission = ChatView.str(p, "permission");
		String directory = ChatView.str(p, "directory");
		if (directory == null || directory.isBlank())
			directory = view.workingFolder != null ? view.workingFolder : view.service.getCurrentSessionDirectory();
		JsonArray patterns = p.getAsJsonArray("patterns");
		if (sid == null || pid == null) {
			return;
		}
		if (!view.surfacedPermissions.add(pid)) return;
		final String permissionDirectory = directory;
		String decisionKey = PermissionDecisions.key(permissionDirectory, permission, patterns);
		String remembered = view.permissionDecisions.remembered(decisionKey);
		String response = remembered != null ? remembered
				: askPermission(sid, permission, permissionDirectory, patterns, decisionKey);
		if (remembered != null) {
			view.setStatus((PermissionDecisions.ALWAYS.equals(remembered) ? "Allowed " : "Denied ")
					+ (permission != null ? permission : "action") + " · remembered answer");
		}
		new Thread(() -> {
			try {
				view.service.respondToPermission(pid, response, permissionDirectory);
			} catch (Exception ex) {
				view.ui(() -> view.setStatus("Permission failed: " + ex.getMessage()));
			}
		}, "opencode-perm").start();
	}

	/**
	 * Asks the user and persists the answer when it is a lasting one. "Always" and "Never" are
	 * remembered for this action/pattern/directory; "Once" and dismissing the dialog are not, so
	 * closing the dialog can never silently deny the same action forever.
	 */
	private String askPermission(String sid, String permission, String directory, JsonArray patterns, String key) {
		view.interactionBlockers++;
		view.publishMonitorState();
		try {
			MessageDialog dialog = new MessageDialog(view.getSite().getShell(), "OpenCode permission", null,
					("Allow " + (permission != null ? permission : "this action") + "?"
							+ "\n\nSession: " + sid + "\nDirectory: " + directory
							+ (patterns != null && !patterns.isEmpty() ? "\n\n" + patterns : "")
							+ "\n\nAlways and Never are remembered for this action and reused in later sessions."),
					MessageDialog.QUESTION, new String[] { "Always", "Once", "Never" }, 1);
			int choice = dialog.open();
			String reply = switch (choice) {
				case 0 -> PermissionDecisions.ALWAYS;
				case 1 -> PermissionDecisions.ONCE;
				default -> PermissionDecisions.REJECT;
			};
			if (choice == 0 || choice == 2) view.permissionDecisions.remember(key, reply);
			return reply;
		} finally {
			view.interactionBlockers--;
			view.publishMonitorState();
		}
	}

	void handleQuestion(JsonObject event) {
		JsonObject props = Events.props(event);
		if (props == null) return;
		String requestId = ChatView.str(props, "id");
		JsonArray questions = props.getAsJsonArray("questions");
		if (requestId == null || questions == null) return;
		view.interactionBlockers++;
		view.publishMonitorState();
		QuestionDialog dialog = new QuestionDialog(view.getSite().getShell(), questions);
		boolean accepted = dialog.open() == org.eclipse.jface.window.Window.OK;
		new Thread(() -> {
			try {
				if (accepted) view.service.replyQuestion(requestId, dialog.answers());
				else view.service.rejectQuestion(requestId);
			} catch (Exception ex) {
				view.ui(() -> view.setStatus("Question response failed: " + ex.getMessage()));
			} finally {
				view.ui(() -> { view.interactionBlockers--; view.publishMonitorState(); });
			}
		}, "opencode-question").start();
	}

	void recoverInteractions(JsonArray permissions, JsonArray questions) {
		String session = view.service.getCurrentSessionId();
		Set<String> sessions = new HashSet<>();
		sessions.add(session);
		try {
			for (JsonElement child : view.service.getSessionDescendants(session))
				sessions.add(ChatView.str(child.getAsJsonObject(), "id"));
		} catch (Exception ex) {
			view.setStatus("Permission recovery incomplete: " + ex.getMessage());
		}
		for (JsonElement element : permissions) {
			JsonObject request = element.getAsJsonObject();
			if (sessions.contains(ChatView.str(request, "sessionID"))) {
				JsonObject event = new JsonObject();
				event.add("properties", request);
				handlePermission(event);
			}
		}
		for (JsonElement element : questions) {
			JsonObject request = element.getAsJsonObject();
			if (session.equals(ChatView.str(request, "sessionID"))) {
				JsonObject event = new JsonObject();
				event.add("properties", request);
				handleQuestion(event);
				break;
			}
		}
	}
}
