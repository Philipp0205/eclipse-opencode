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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
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

	final OpenCodeService service = new OpenCodeService();
	final CommandRouter commandRouter = new CommandRouter(this);
	final ChatController controller = new ChatController(this);
	private static final java.util.concurrent.ConcurrentHashMap<String, ExplorerTarget> EXPLORER_TARGETS = new java.util.concurrent.ConcurrentHashMap<>();
	private record ExplorerTarget(String directory, String sessionId) { }

	private Button sessionButton;
	private final List<JsonObject> allSessions = new ArrayList<>();
	Combo agentCombo;
	Button modelButton;
	ConversationBrowser conversation;
	private AttachedFilesBar attached;
	ChangedFilesBar changedFiles;
	private Composite queueBar;
	Text input;
	org.eclipse.swt.widgets.Button sendButton;
	Label status;
	private Label activity;
	SpinnerAnimator spinner;
	/** SWT-thread state for the single activity animation. */
	boolean activityIndicatorActive;
	SlashCommandPopup slashPopup;

	final List<ModelChoice> modelChoices = new ArrayList<>();
	private final Map<String, AgentDefault> agentDefaults = new HashMap<>();
	private final List<String> agentNames = new ArrayList<>();
	List<CommandInfo> commands = List.of();
	private final ModelPicker modelPicker = new ModelPicker();
	private final ModelPicker sessionPicker = new ModelPicker();
	final Diffs diffs = new Diffs();
	final java.util.LinkedHashSet<String> manualAttachments = new java.util.LinkedHashSet<>();
	final java.util.HashSet<String> excludedAttachments = new java.util.HashSet<>();
	final List<String> promptHistory = new ArrayList<>();
	final MessageQueue<QueuedPrompt> promptQueue = new MessageQueue<>();
	int promptHistoryIndex;

	/** messageID -> role (user/assistant), learned from message.updated events. */
	final Map<String, String> roles = new HashMap<>();
	final Map<String, java.util.LinkedHashMap<String, JsonObject>> liveParts = new HashMap<>();
	final Map<String, Double> messageCosts = new HashMap<>();
	private Image sendImage;
	private Image stopImage;
	private Image attachImage;
	int mcpServers;
	private int lspServers;
	private int pluginInfoCount;
	long contextUsed;
	long contextLimit;
	double sessionCost;
	String workingFolder;
	boolean providerConnected;
	private boolean explicitModelOverride;
	private String lastMonitorStatus;
	boolean deleting;
	/** Delegated (subagent / task-tool) child sessions, keyed by their own session id.
	 * Only running/blocked children are kept — terminal ones are removed immediately so
	 * the sessions view never shows a finished subagent as still active.
	 * These are {@code ConcurrentHashMap}s for safe individual reads/writes, but every
	 * mutation site in this class runs on the SWT UI thread (onEvent, dispose, and the
	 * reconcileChildSessions background thread only mutates inside ui(...)) — keep it
	 * that way, since the check-then-act sequences across the two maps are not atomic. */
	static volatile boolean dashboardRefreshScheduled;
	private final org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener fontListener = event -> {
		if ("chatFontSize".equals(event.getKey()) && conversation != null && !conversation.isDisposed())
			conversation.getDisplay().asyncExec(() -> conversation.setChatFontSize(ChatPreferences.fontSize()));
	};
	boolean attachAllOpen;
	int interactionBlockers;
	/** Permission request IDs already surfaced by SSE or pending recovery. */
	final java.util.Set<String> surfacedPermissions = java.util.concurrent.ConcurrentHashMap.newKeySet();
	final PermissionDecisions permissionDecisions = new PermissionDecisions(ChatPreferences.node());
	int turnGeneration;
	String activeConversationActivity;
	String activeAssistantMessage;
	String runningSessionId;
	volatile boolean busy;
	private org.eclipse.ui.IPartListener2 editorListener;
	ModelChoice selectedModel;
	SessionRestoreStore sessionRestore;
	record QueuedPrompt(String id, String text, String agent, ModelChoice model,
			List<OpenEditors.Attached> attachments) { }
	private record SessionChoice(String id, String title) { }
	private record AgentDefault(String model, String variant) { }

	@Override
	public void createPartControl(Composite parent) {
		GridLayout root = new GridLayout(1, false);
		root.marginHeight = 3;
		root.marginWidth = 3;
		root.verticalSpacing = 3;
		parent.setLayout(root);
		workingFolder = workspaceRoot();
		sessionRestore = new SessionRestoreStore(ChatPreferences.node(), getViewSite().getSecondaryId());

		createToolbar(parent);
		createMessageArea(parent);
		changedFiles = new ChangedFilesBar(parent, diffs);
		createQueueBar(parent);
		createAttachedBar(parent);
		createInput(parent);
		createStatusBar(parent);
		setStatus("Starting opencode…");
		conversation.setChatFontSize(ChatPreferences.fontSize());
		ChatPreferences.node().addPreferenceChangeListener(fontListener);

		startServerAsync();
		watchStartup();
		installEditorListener();
		publishMonitorState();
	}

	private void installEditorListener() {
		editorListener = new org.eclipse.ui.IPartListener2() {
			@Override public void partActivated(org.eclipse.ui.IWorkbenchPartReference ref) { refreshIfEditor(ref); }
			@Override public void partOpened(org.eclipse.ui.IWorkbenchPartReference ref) { refreshIfEditor(ref); }
			@Override public void partClosed(org.eclipse.ui.IWorkbenchPartReference ref) { refreshIfEditor(ref); }
			private void refreshIfEditor(org.eclipse.ui.IWorkbenchPartReference ref) {
				if (ref instanceof org.eclipse.ui.IEditorReference && attached != null && !attached.isDisposed())
					attached.getDisplay().asyncExec(() -> { if (!attached.isDisposed()) refreshAttached(); });
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

	void updateQueueBar() {
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

	public void closeFromMonitor() {
		try {
			var site = getSite();
			if (site != null && site.getPage() != null) site.getPage().hideView(this);
		} catch (RuntimeException ignored) {
			// The workbench may already be closing this view or its page.
		}
	}

	public void renameFromMonitor() { renameSession(); }
	public void deleteFromMonitor() {
		String id = service.getCurrentSessionId();
		deleting = true;
		new Thread(() -> { try { service.deleteSession(id); ui(() -> getSite().getPage().hideView(this)); } catch (Exception ex) {
			deleting = false;
			ui(() -> setStatus("Delete failed: " + ex.getMessage()));
		} }, "opencode-delete-monitor").start();
	}
	void renameSession() {
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

	void sessionAction(String action) {
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
				if ("fork".equals(action)) {
					service.switchSession(str(session, "id"));
					sessionRestore.persist(service.getCurrentSessionId(), service.getCurrentSessionDirectory());
					JsonArray messages = service.getMessages(service.getCurrentSessionId());
					JsonArray sessions = service.listSessions();
					ui(() -> { fillSessions(sessions); renderHistory(messages); workingFolder = service.getCurrentSessionDirectory(); updateStatus(); });
				} else refreshSessionsAsync();
			} catch (Exception ex) { ui(() -> setStatus(action + " failed: " + ex.getMessage())); }
		}, "opencode-session-action").start();
	}

	void refreshSessionsAsync() {
		try { JsonArray sessions = service.listSessions(); ui(() -> fillSessions(sessions)); }
		catch (Exception ex) { ui(() -> setStatus("Session refresh failed: " + ex.getMessage())); }
	}

	private void copyToClipboard(String text) {
		if (text == null) return;
		org.eclipse.swt.dnd.Clipboard clipboard = new org.eclipse.swt.dnd.Clipboard(getSite().getShell().getDisplay());
		try { clipboard.setContents(new Object[] { text }, new org.eclipse.swt.dnd.Transfer[] {
				org.eclipse.swt.dnd.TextTransfer.getInstance() }); } finally { clipboard.dispose(); }
	}

	/**
	 * Agent, model, activity and session metrics share one compact row below the prompt. They used
	 * to occupy two rows with "Agent:"/"Model:" labels, which cost vertical space the conversation
	 * needs more; the controls carry accessible names and tooltips instead.
	 */
	private void createStatusBar(Composite parent) {
		Composite bar = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(5, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		bar.setLayout(layout);
		bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		agentCombo = new Combo(bar, SWT.READ_ONLY);
		agentCombo.setText("Loading agents…");
		agentCombo.setToolTipText("Agent");
		named(agentCombo, "Agent");
		GridData agentGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
		agentGd.widthHint = 110;
		agentCombo.setLayoutData(agentGd);

		modelButton = new Button(bar, SWT.PUSH | SWT.FLAT);
		modelButton.setText("Loading default model…");
		modelButton.setToolTipText("Model");
		named(modelButton, "Model");
		modelButton.setAlignment(SWT.LEFT);
		GridData modelGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
		modelGd.widthHint = 180;
		modelButton.setLayoutData(modelGd);
		modelButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				openModelPicker();
			}
		});

		activity = new Label(bar, SWT.NONE);
		spinner = new SpinnerAnimator(activity);
		status = new Label(bar, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		status.addListener(SWT.MouseDoubleClick, e -> {
			String text = status.getToolTipText();
			if (text != null && !text.equals(status.getText())) new InfoDialog(getSite().getShell(), text).open();
		});
		Button contextButton = new Button(bar, SWT.PUSH | SWT.FLAT); contextButton.setText("Info");
		contextButton.setToolTipText("Show OpenCode session info");
		contextButton.addListener(SWT.Selection, e -> showContext());
	}

	private static void named(Control control, String name) {
		control.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
			@Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent e) { e.result = name; }
		});
	}

	private void createAttachedBar(Composite parent) {
		attached = new AttachedFilesBar(parent, ChatPreferences.attachedHeight(), ChatPreferences::setAttachedHeight);
		attached.chips().setMenu(new Menu(attached.chips()));
	}

	/** Probe hook: however many tabs are open, the chips must never outgrow their own area. */
	boolean attachedAreaIsBounded() {
		return attached != null && !attached.isDisposed() && attached.withinBounds();
	}

	/**
	 * Probe hook: the case the bounded check above cannot see, because it depends on how many tabs
	 * happen to be open. Attaches far more files than fit, then asserts the area kept its height and
	 * moved the surplus into the scrollbar instead of pushing the conversation away.
	 */
	boolean attachedAreaStaysOneRowWithManyTabs() {
		if (attached == null || attached.isDisposed()) return false;
		int before = attached.height();
		var restore = new java.util.LinkedHashSet<>(manualAttachments);
		try {
			for (int i = 0; i < 40; i++) manualAttachments.add("/tmp/opencode-probe-attachment-" + i + ".java");
			refreshAttached();
			return attached.height() == before && attached.scrolls() && attachedAreaIsBounded();
		} finally {
			manualAttachments.clear();
			manualAttachments.addAll(restore);
			refreshAttached();
		}
	}

	/** Probe hook: agent, model, activity, status and Info must stay on one row. */
	boolean statusBarIsSingleRow() {
		Composite bar = status.getParent();
		if (agentCombo.getParent() != bar || modelButton.getParent() != bar) return false;
		int tallest = 0;
		for (Control child : bar.getChildren()) tallest = Math.max(tallest, child.getSize().y);
		return tallest > 0 && bar.getSize().y <= tallest + 2;
	}

	private void createMessageArea(Composite parent) {
		conversation = new ConversationBrowser(parent);
		conversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
	}

	private void createInput(Composite parent) {
		Composite row = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(2, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		row.setLayout(layout);
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
			if (e.keyCode == SWT.PAGE_UP || e.keyCode == SWT.PAGE_DOWN) {
				conversation.scrollPage(e.keyCode == SWT.PAGE_UP);
				e.doit = false;
				return;
			}
			boolean historyKey = e.keyCode == SWT.ARROW_UP || e.keyCode == SWT.ARROW_DOWN;
			int caret = input.getCaretPosition();
			boolean suitable = input.getLineCount() <= 1 || input.getText().isBlank()
					|| (e.keyCode == SWT.ARROW_UP && caret == 0)
					|| (e.keyCode == SWT.ARROW_DOWN && caret == input.getCharCount());
			if (historyKey && suitable && ((e.stateMask & SWT.ALT) != 0 || input.getLineCount() <= 1 || input.getText().isBlank()
					|| (e.keyCode == SWT.ARROW_UP && caret == 0) || (e.keyCode == SWT.ARROW_DOWN && caret == input.getCharCount()))) {
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

		Composite buttons = new Composite(row, SWT.NONE);
		buttons.setLayout(new GridLayout(1, false));
		buttons.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
		org.eclipse.swt.widgets.Button sendBtn = new org.eclipse.swt.widgets.Button(buttons, SWT.PUSH | SWT.FLAT);
		sendButton = sendBtn;
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

		org.eclipse.swt.widgets.Button stopBtn = new org.eclipse.swt.widgets.Button(buttons, SWT.PUSH | SWT.FLAT);
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

	/** Longest the view may claim to be starting before it says what is wrong instead. */
	private static final int STARTUP_WATCHDOG_MS = 120_000;
	private volatile boolean startupFinished;

	/**
	 * Replace a permanent "Starting opencode…" with something actionable.
	 *
	 * <p>Startup is a chain of server calls, and every one of them can be slowed or stalled by
	 * the opencode server itself. Without this, a stalled step leaves the view looking like it
	 * is still loading, with no hint that the local server is the problem.
	 */
	private void watchStartup() {
		Display.getDefault().timerExec(STARTUP_WATCHDOG_MS, () -> {
			if (startupFinished || input == null || input.isDisposed()) return;
			String output = service.serverOutput().trim();
			conversation.putMessage("startup-stalled", "assistant",
					"**opencode is not responding.**\n\nThe local `opencode serve` process started but "
							+ "did not finish answering this view's startup requests within "
							+ (STARTUP_WATCHDOG_MS / 1000) + "s.\n\n"
							+ (output.isEmpty() ? "It printed no output.\n" : "Its output was:\n\n```\n" + output + "\n```\n")
							+ "\nClose and reopen the view to retry. If it keeps happening, check that "
							+ "`opencode serve` starts normally in this workspace from a terminal.");
			setStatus("opencode did not respond while starting · see the message above");
		});
	}

	private void startServerAsync() {
		new Thread(() -> {
			try {
				service.initialize(workspaceRoot());
				String secondaryId = getViewSite().getSecondaryId();
				ExplorerTarget target = secondaryId == null ? null : EXPLORER_TARGETS.remove(secondaryId);
				String savedSession = sessionRestore.loadSessionId();
				String savedDirectory = sessionRestore.loadDirectory();
				JsonArray sessions;
				if (target != null) {
					sessions = service.listSessions(target.directory());
				} else if (savedDirectory != null) {
					JsonArray fromSavedDirectory;
					try {
						fromSavedDirectory = service.listSessions(savedDirectory);
					} catch (Exception ex) {
						// The saved directory may no longer exist (deleted/unmounted); fall back to the
						// workspace-root listing instead of failing startup entirely.
						sessionRestore.clear();
						fromSavedDirectory = service.listSessions();
					}
					sessions = fromSavedDirectory;
				} else {
					sessions = service.listSessions();
				}
				boolean restored = false;
				if (target != null && containsSession(sessions, target.sessionId())) {
					service.switchSession(target.directory(), target.sessionId());
					sessionRestore.persist(target.sessionId(), target.directory()); restored = true;
				} else if (savedSession != null && containsSession(sessions, savedSession)) {
					try {
						if (savedDirectory != null) {
							service.switchSession(savedDirectory, savedSession);
						} else {
							service.switchSession(savedSession);
						}
						sessionRestore.persist(savedSession, service.getCurrentSessionDirectory());
						restored = true;
					} catch (Exception ignored) {
						// Fall through to the normal new-session startup path.
					}
				} else if (savedSession != null) {
					sessionRestore.clear();
				}
				if (!restored) {
					service.createSession();
					sessionRestore.persist(service.getCurrentSessionId(), service.getCurrentSessionDirectory());
				}
				List<JsonObject> agents = service.getAgents();
				JsonArray availableSessions = service.listSessions();
				String selectedId = service.getCurrentSessionId();
				JsonArray startupMessages = service.getMessages(selectedId);
				JsonObject providers = service.listProviders();
				JsonObject providerStatus = service.providerStatus();
				JsonObject mcp = service.getMcpStatus();
				List<CommandInfo> loadedCommands = service.listCommands();
				JsonArray pendingPermissions = service.listPendingPermissions();
				JsonArray pendingQuestions = service.listPendingQuestions();
				JsonObject config = service.getConfig();
				String configModel = config.has("model") ? config.get("model").getAsString() : null;
				String defaultAgent = config.has("default_agent") ? config.get("default_agent").getAsString()
						: config.has("defaultAgent") ? config.get("defaultAgent").getAsString() : null;
				startupFinished = true;
				ui(() -> {
					mcpServers = connectedMcpServers(mcp);
					providerConnected = providerStatus.getAsJsonArray("connected") != null
							&& !providerStatus.getAsJsonArray("connected").isEmpty();
					List<CommandInfo> allCommands = CommandRouter.mergedCommands(loadedCommands);
					commands = List.copyOf(allCommands);
					fillAgents(agents, defaultAgent);
					fillSessions(availableSessions);
					renderHistory(startupMessages);
					fillModels(providers, configModel);
					service.watchSessionEvents(event -> ui(() -> controller.onSessionEvent(event)));
					refreshAttached();
					updateStatus();
					if (!providerConnected) {
						setStatus("OpenCode needs a provider · type /connect");
						conversation.putMessage("setup-required", "assistant",
								"**OpenCode is installed, but no AI provider is connected.**\n\nType `/connect` to sign in or add an API key.");
					}
					controller.recoverInteractions(pendingPermissions, pendingQuestions);
					if (Boolean.getBoolean("opencode.wholeViewProbe") && getViewSite().getSecondaryId() == null) {
						new ChatViewProbe(this).run();
					}
				});
			} catch (Exception ex) {
				startupFinished = true;
				ui(() -> setStatus("Failed to start opencode: " + ex.getMessage()));
			}
		}, "opencode-startup").start();
	}

	/** Populate the model picker from config/providers; preselect the config model. */
	void fillModels(JsonObject providers, String configModel) {
		modelChoices.clear(); modelChoices.addAll(ModelChoice.from(providers));
		selectedModel = modelChoices.stream().filter(choice -> choice.model().equals(configModel) && choice.variant() == null)
				.findFirst().orElse(modelChoices.isEmpty() ? null : modelChoices.get(0));
		if (!explicitModelOverride) applyModel(selectedModel);
		if (!explicitModelOverride) selectAgentDefault();
	}

	void openModelPicker() {
		modelPicker.toggle(modelButton, modelChoices, ModelChoice::popupLabel, "Search models", this::selectModel);
	}

	void selectModel(ModelChoice selected) {
		explicitModelOverride = true;
		applyModel(selected);
	}

	private void applyModel(ModelChoice selected) {
		selectedModel = selected;
		modelButton.setText(selected != null ? selected.compactLabel() : "");
		service.setModel(selected != null ? selected.model() : null);
		contextLimit = selected != null ? selected.contextLimit() : 0;
		updateStatus();
	}

	/** Rebuild the attached-resources chips from the currently open editors. */
	private void refreshAttached() {
		if (attached == null || attached.isDisposed()) {
			return;
		}
		Composite attachedBar = attached.chips();
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
		attached.chipsChanged();
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

	static String workspaceRoot() {
		var root = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
		String eclipseRoot = root.getLocation() != null ? root.getLocation().toOSString() : System.getProperty("user.dir");
		return WorkspaceRoot.resolve(System.getenv("ENV_SCM_WORKSPACE_ROOT"), eclipseRoot);
	}

	void fillAgents(List<JsonObject> agents, String defaultAgent) {
		agentDefaults.clear();
		agentCombo.removeAll();
		agentNames.clear();
		int selected = -1;
		int fallback = -1;
		for (JsonObject a : agents) {
			String name = a.get("name").getAsString();
			String description = str(a, "description");
			AgentDescriptions.put(name, description);
			agentCombo.add(displayName(name, description));
			agentNames.add(name);
			AgentDefault model = agentModel(a.get("model"));
			if (model != null) agentDefaults.put(name, model);
			if (name.equals(defaultAgent)) selected = agentCombo.getItemCount() - 1;
			if ("build".equals(name)) fallback = agentCombo.getItemCount() - 1;
		}
		if (selected < 0) selected = fallback;
		if (agentCombo.getItemCount() > 0) {
			agentCombo.select(selected >= 0 ? selected : 0);
			agentCombo.addSelectionListener(new SelectionAdapter() {
				@Override public void widgetSelected(SelectionEvent e) {
					explicitModelOverride = false;
					selectAgentDefault();
				}
			});
			selectAgentDefault();
		}
	}

	/** The combo shows the agent's leading emoji (if its description starts with one) plus its
	 * name; the underlying agent name sent to OpenCode always comes from {@link #selectedAgentName()},
	 * never from the combo's display text. */
	static String displayName(String name, String description) {
		String emoji = leadingEmoji(description);
		return emoji.isEmpty() ? name : emoji + " " + name;
	}

	/** Returns the description's leading emoji/symbol (plus an optional variation selector), or
	 * "" if the description doesn't start with one. Heuristic: emoji and symbol code points sit
	 * well above ordinary Latin text (U+2000 and up), which covers agent descriptions in practice. */
	static String leadingEmoji(String description) {
		if (description == null || description.isBlank()) return "";
		int first = description.codePointAt(0);
		if (first < 0x2000) return "";
		int firstLen = Character.charCount(first);
		int next = firstLen < description.length() ? description.codePointAt(firstLen) : -1;
		int len = next == 0xFE0F ? firstLen + Character.charCount(next) : firstLen;
		return description.substring(0, len);
	}

	/** The agent name to send to OpenCode for the currently selected combo entry — never the
	 * combo's own display text, which may be prefixed with an emoji (see {@link #displayName}). */
	String selectedAgentName() {
		int i = agentCombo.getSelectionIndex();
		return i >= 0 && i < agentNames.size() ? agentNames.get(i) : null;
	}

	private static AgentDefault agentModel(JsonElement value) {
		if (value == null || value.isJsonNull()) return null;
		if (value.isJsonPrimitive()) return new AgentDefault(value.getAsString(), null);
		if (!value.isJsonObject()) return null;
		JsonObject model = value.getAsJsonObject();
		String provider = str(model, "providerID");
		String id = str(model, "modelID");
		String variant = str(model, "variant");
		return provider != null && id != null ? new AgentDefault(provider + "/" + id, variant) : null;
	}

	private void selectAgentDefault() {
		if (explicitModelOverride || agentCombo.getSelectionIndex() < 0) return;
		String name = selectedAgentName();
		AgentDefault configured = agentDefaults.get(name);
		if (configured == null) return;
		selectedModel = modelChoices.stream().filter(m -> configured.model().equals(m.model())
				&& java.util.Objects.equals(configured.variant(), m.variant())).findFirst().orElse(selectedModel);
		applyModel(selectedModel);
	}

	void resetAgentModel() {
		explicitModelOverride = false;
		selectAgentDefault();
	}

	void fillSessions(JsonArray sessions) {
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

	void openSessionPicker() {
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
				sessionRestore.persist(sid, service.getCurrentSessionDirectory());
				JsonArray sessions = service.listSessions();
				JsonArray msgs = service.getMessages(sid);
				JsonArray permissions = service.listPendingPermissions();
				JsonArray questions = service.listPendingQuestions();
				ui(() -> {
					workingFolder = sessionDirectory(selectedSession);
					resetAgentModel();
					fillSessions(sessions);
					renderHistory(msgs);
					controller.recoverInteractions(permissions, questions);
				});
			} catch (Exception ex) {
				ui(() -> setStatus("Switch failed: " + ex.getMessage()));
			}
		}, "opencode-switch").start();
	}

	static void openFromExplorer(String directory, String id) {
		if (directory == null || id == null) return;
		ChatView existing = ChatViewRegistry.find(directory, id);
		try {
			var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			if (existing != null) { page.activate(existing); return; }
			String secondary = "explorer-" + java.util.UUID.randomUUID();
			EXPLORER_TARGETS.put(secondary, new ExplorerTarget(directory, id));
			page.showView(ID, secondary, org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
		} catch (Exception e) { EXPLORER_TARGETS.values().removeIf(t -> directory.equals(t.directory()) && id.equals(t.sessionId())); }
	}

	boolean matchesExplorerTarget(String directory, String sessionId) {
		return sessionId != null && sessionId.equals(service.getCurrentSessionId()) && directory != null
				&& directory.equals(canonicalDirectory(service.getCurrentSessionDirectory()));
	}
	private static String canonicalDirectory(String directory) { try { return java.nio.file.Path.of(directory).toRealPath().toString(); } catch (Exception e) { return java.nio.file.Path.of(directory).toAbsolutePath().normalize().toString(); } }

	void newSessionAsync() {
		if (busy || !promptQueue.isEmpty()) {
			setStatus("Finish or remove queued messages before creating a session");
			return;
		}
		org.eclipse.swt.widgets.DirectoryDialog dialog = new org.eclipse.swt.widgets.DirectoryDialog(getSite().getShell());
		dialog.setText("New OpenCode session"); dialog.setMessage("Select the session working directory");
		dialog.setFilterPath(service.getCurrentSessionDirectory() != null ? service.getCurrentSessionDirectory() : workspaceRoot());
		String directory = dialog.open(); if (directory == null) return;
		new Thread(() -> {
			try {
				service.createSession(directory);
				sessionRestore.persist(service.getCurrentSessionId(), service.getCurrentSessionDirectory());
				String sessionDirectory = service.getCurrentSessionDirectory();
				JsonArray sessions = service.listSessions();
				ui(() -> {
					workingFolder = sessionDirectory;
					resetAgentModel();
					clearMessages();
					fillSessions(sessions);
					setStatus("New session");
				});
			} catch (Exception ex) {
				ui(() -> setStatus("New session failed: " + ex.getMessage()));
			}
		}, "opencode-new").start();
	}

	private static boolean containsSession(JsonArray sessions, String id) {
		for (JsonElement element : sessions) {
			if (element.isJsonObject() && id.equals(str(element.getAsJsonObject(), "id"))) return true;
		}
		return false;
	}

	/** Invoked by the declarative view-toolbar command. */
	public void startNewSession() {
		newSessionAsync();
	}

	void renderHistory(JsonArray msgs) {
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
		conversation.setConversation(normalizeMessages(msgs));
		updateStatus();
	}

	static JsonArray normalizeMessages(JsonArray messages) {
		JsonArray normalized = new JsonArray();
		for (JsonElement element : messages) {
			JsonObject message = element.getAsJsonObject().deepCopy();
			JsonObject info = message.getAsJsonObject("info");
			if (info == null) continue;
			if (!message.has("parts") || message.get("parts").isJsonNull()) message.add("parts", new JsonArray());
			normalized.add(message);
		}
		return normalized;
	}

	// ---- sending / streaming ---------------------------------------------

	// ---- sending / streaming ---------------------------------------------

	void send() {
		controller.send();
	}

	/** Public command bridge; the command handler delegates to the originating view. */
	public void showDiffs() { commandRouter.handle("diff", null); }

	/** Explicit, view-owned bridge for the untitled prompt editor. */
	OpenEditors.PromptTarget promptTarget() {
		return new OpenEditors.PromptTarget(text -> { if (!input.isDisposed()) { input.setText(text); input.setFocus(); sendButton.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event()); } });
	}

	void abortAsync() {
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
						controller.drainQueue();
					}
				});
			}
		}, "opencode-abort").start();
	}

	private void clearMessages() {
		roles.clear();
		liveParts.clear();
		conversation.clear();
	}

	// ---- helpers ----------------------------------------------------------

	static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	static String toolKey(JsonObject part) {
		String id = str(part, "id");
		if (id == null) {
			id = str(part, "callID");
		}
		return id != null ? id : str(part, "messageID") + ":" + str(part, "tool");
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

	void startActivity(String text) {
		setStatus(text);
		updateActivityIndicator();
	}

	void stopActivity() {
		removeConversationActivity();
		updateActivityIndicator();
		updateStatus();
	}

	/** Must be called on the SWT thread; keeps the animation independent of root busy state. */
	private void updateActivityIndicator() {
		boolean wanted = busy;
		if (wanted == activityIndicatorActive) return;
		activityIndicatorActive = wanted;
		if (wanted) spinner.start();
		else spinner.stop();
	}

	void removeConversationActivity() {
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

	void updateContext(JsonObject event) {
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

	String metrics() {
		String folder = workingFolder != null ? java.nio.file.Path.of(workingFolder).getFileName().toString() : "workspace";
		String context = contextLimit > 0 ? Math.round(contextUsed * 100.0 / contextLimit) + "% context" : "context —";
		return String.format(java.util.Locale.ROOT, "$%.2f · %s · %s", sessionCost, context, folder);
	}

	void updateStatus() {
		updateActivityIndicator();
		setStatus((busy ? "Thinking · " : "Ready · ") + metrics());
		publishMonitorState();
	}

	void publishMonitorState() {
		if (sessionButton == null || sessionButton.isDisposed()) return;
		ChatViewRegistry.Status current = ChatViewRegistry.status(busy, interactionBlockers);
		ChatViewRegistry.update(this, sessionButton.getText().isBlank() ? "New Session" : sessionButton.getText(), current);
		if (lastMonitorStatus != null && !lastMonitorStatus.equals(current.name())) {
			if (current == ChatViewRegistry.Status.blocked || current == ChatViewRegistry.Status.done) {
				var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window != null && window.getShell() != null) window.getShell().getDisplay().beep();
				if (status != null && !status.isDisposed()) status.setToolTipText("Session " + current.name());
			}
		}
		lastMonitorStatus = current.name();
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
				+ "\nConnected MCP servers: " + mcpServers
				+ "\nopencode version: " + (service.getServerVersion() != null ? service.getServerVersion() : "unknown");
		new InfoDialog(getSite().getShell(), details).open();
	}

	void setStatus(String s) {
		if (status != null && !status.isDisposed()) {
			status.setText(s);
			if (s == null || (!s.startsWith("OpenCode error") && !s.startsWith("Prompt failed") && !s.startsWith("Failed")))
				status.setToolTipText(null);
			if (s != null && s.length() > 120) status.setToolTipText(s);
		}
	}

	/** Small read-only snapshot used by the optional workbench sidebar. */
	public String sidebarSnapshot() {
		return "Session: " + sessionButton.getText() + "\nWorking folder: " + workingFolder
				+ "\nContext: " + contextUsed + " / " + contextLimit + " tokens\nMCP: " + mcpServers
				+ " connected\nStatus: " + (busy ? "running" : "ready");
	}
	public String sidebarDetails() { return sidebarSnapshot() + "\nLSP: " + lspServers
				+ " configured\nPlugins / events: " + pluginInfoCount + " commands"; }

	void ui(Runnable r) {
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
		ChatViewRegistry.active(this);
		if (input != null && !input.isDisposed()) {
			input.setFocus();
		}
	}

	@Override
	public void dispose() {
		ChatPreferences.node().removePreferenceChangeListener(fontListener);
		ChatViewRegistry.remove(this);
		ChildSessionTracker.releaseOwner(this);
		if (editorListener != null) getSite().getPage().removePartListener(editorListener);
		if (slashPopup != null) slashPopup.close();
		modelPicker.close();
		sessionPicker.close();
		new Thread(service::dispose, "opencode-chat-cleanup").start();
		if (spinner != null) spinner.stop();
		if (sendImage != null && !sendImage.isDisposed()) sendImage.dispose();
		if (attachImage != null && !attachImage.isDisposed()) attachImage.dispose();
		super.dispose();
	}
}
