package com.opencode.eclipse.ui;

import java.text.DateFormat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opencode.eclipse.core.OpenCodeService;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;

/** Cross-project, read-only view of sessions known to the local OpenCode server. */
public final class SessionsExplorerView extends ViewPart {
    public static final String ID = "com.opencode.eclipse.ui.sessionsExplorerView";
    private Tree tree;
    private Label message;
    private volatile OpenCodeService explorerService;
    private volatile boolean disposed;
    private int refreshGeneration;
    private String lastSignature;
    // Generous enough to cover a cold "opencode serve" start (up to ~25s, see
    // OpenCodeService.initialize) plus a full listSessions round trip, even when several
    // ChatView/SessionsExplorerView instances restore concurrently after an Eclipse restart.
    private static final int LOAD_TIMEOUT_MS = 45_000;
    private final Runnable registryRefresh = this::scheduleRefresh;
    private static final java.util.List<SessionsExplorerView> INSTANCES = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Called whenever a subagent/child session starts, changes status, or finishes so any
     * open Sessions Explorer picks it up live instead of only on manual refresh. */
    static void refreshAll() { INSTANCES.forEach(SessionsExplorerView::scheduleRefresh); }

    /** Test/probe support: true if any open Sessions Explorer currently shows at least one
     * nested child (subagent) session anywhere in its tree. */
    static boolean anyChildSessionVisible() {
        for (SessionsExplorerView view : INSTANCES) {
            if (view.tree == null || view.tree.isDisposed()) continue;
            if (containsChildItem(view.tree.getItems())) return true;
        }
        return false;
    }

    private static boolean containsChildItem(TreeItem[] items) {
        for (TreeItem item : items) {
            if (item.getData() instanceof SessionTarget target && target.child()) return true;
            if (containsChildItem(item.getItems())) return true;
        }
        return false;
    }

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        message = new Label(parent, SWT.WRAP);
        message.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        message.setText("Sessions are grouped by project and working folder.");
        tree = new Tree(parent, SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tree.addListener(SWT.DefaultSelection, e -> {
            TreeItem[] selected = tree.getSelection();
            if (selected.length == 1 && selected[0].getData() instanceof SessionTarget target) {
                ChatView.openFromExplorer(target.directory(), target.id());
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh sessions") {
            @Override public void run() { refreshAsync(); }
        });
        ChatViewRegistry.addListener(registryRefresh);
        INSTANCES.add(this);
        refreshAsync();
        schedulePoll();
    }

    private void scheduleRefresh() {
        if (tree != null && !tree.isDisposed()) tree.getDisplay().asyncExec(this::refreshAsync);
    }

    private void refreshAsync() { refreshAsync(true); }

    /** Background OpenCode server processes each run their own SSE `/event` stream and do not
     * broadcast session activity across processes (verified: a session created via one
     * "opencode serve" instance never appears on another instance's /event stream, even though
     * GET /session reflects it immediately since session storage itself is shared on disk). So
     * a running subagent spawned by a different ChatView/process — or an external client
     * entirely — can only be picked up here by re-polling, not by reacting to this view's own
     * service events. {@code userInitiated} toggles the "Loading…"/disable/timeout chrome, which
     * only makes sense for an explicit user-triggered refresh, not a quiet background poll. */
    private void refreshAsync(boolean userInitiated) {
        if (tree == null || tree.isDisposed() || disposed) return;
        int generation = ++refreshGeneration;
        if (userInitiated) {
            tree.setEnabled(false);
            message.setText("Loading sessions…");
            tree.getDisplay().timerExec(LOAD_TIMEOUT_MS, () -> {
                if (!disposed && generation == refreshGeneration && tree != null && !tree.isDisposed()
                        && !tree.isEnabled()) {
                    refreshGeneration++;
                    error(new Exception("OpenCode did not respond while listing sessions (timed out)"));
                }
            });
        }
        new Thread(() -> {
            try {
                OpenCodeService service = explorerService;
                if (service == null) {
                    OpenCodeService candidate = new OpenCodeService();
                    candidate.initialize(effectiveWorkspaceRoot());
                    synchronized (this) {
                        service = explorerService;
                        if (service == null) {
                            explorerService = service = candidate;
                        } else {
                            candidate.dispose();
                        }
                    }
                }
                List<String> roots = projectRoots();
                Map<String, JsonObject> sessions = new LinkedHashMap<>();
                List<String> errors = new ArrayList<>();
                OpenCodeService queryService = service;
                int parallelism = Math.max(1, Math.min(4, roots.size()));
                ExecutorService pool = Executors.newFixedThreadPool(parallelism);
                CompletionService<RootResult> completed = new ExecutorCompletionService<>(pool);
                try {
                    for (String root : roots) completed.submit(() -> listRoot(queryService, root));
                    for (int i = 0; i < roots.size(); i++) {
                        RootResult result = completed.take().get();
                        result.sessions().forEach((key, session) -> sessions.putIfAbsent(key, session));
                        if (result.error() != null) errors.add(result.error());
                    }
                } finally {
                    pool.shutdownNow();
                }
                ui(() -> { if (generation == refreshGeneration) fill(new ArrayList<>(sessions.values()), errors); });
            } catch (Exception ex) {
                if (userInitiated) ui(() -> { if (generation == refreshGeneration) error(ex); });
            }
        }, "opencode-session-explorer").start();
    }

    /** Poll interval while the view is visible, so running subagents spawned by any process
     * (another ChatView, or an external opencode client entirely) show up without the user
     * having to hit "Refresh sessions" manually. Quiet: no "Loading…"/disable chrome. */
    private static final int POLL_INTERVAL_MS = 5_000;

    private void schedulePoll() {
        if (tree == null || tree.isDisposed() || disposed) return;
        tree.getDisplay().timerExec(POLL_INTERVAL_MS, () -> {
            if (disposed || tree == null || tree.isDisposed()) return;
            if (tree.isEnabled()) refreshAsync(false); // skip while a user-initiated load is in flight
            schedulePoll();
        });
    }

    private static RootResult listRoot(OpenCodeService service, String root) {
        try {
            Map<String, JsonObject> sessions = new LinkedHashMap<>();
            for (var element : service.listSessions(root)) {
                if (!element.isJsonObject()) continue;
                JsonObject session = element.getAsJsonObject();
                String id = value(session, "id");
                String directory = canonical(value(session, "directory").isBlank() ? root : value(session, "directory"));
                if (!id.isBlank()) sessions.putIfAbsent(id + "\n" + directory, sessionWithDirectory(session, directory));
            }
            return new RootResult(sessions, null);
        } catch (Exception ex) {
            return new RootResult(Map.of(), root + ": " + message(ex));
        }
    }

    private void fill(List<JsonObject> sessions, List<String> errors) {
        if (tree == null || tree.isDisposed()) return;
        String signature = signature(sessions, errors);
        if (signature.equals(lastSignature)) {
            setSummaryMessage(sessions, errors);
            tree.setEnabled(true);
            return; // nothing changed since the last render: skip the full tree rebuild
        }
        lastSignature = signature;
        tree.setRedraw(false);
        try {
            tree.removeAll();
            Map<String, TreeItem> projects = new LinkedHashMap<>();
            Map<String, TreeItem> folders = new LinkedHashMap<>();
            Map<String, JsonObject> byId = new LinkedHashMap<>();
            Map<String, List<JsonObject>> childrenByParent = new LinkedHashMap<>();
            List<JsonObject> roots = new ArrayList<>();
            for (JsonObject session : sessions) {
                String id = value(session, "id");
                if (!id.isBlank()) byId.put(id, session);
            }
            for (JsonObject session : sessions) {
                String parentId = value(session, "parentID");
                if (parentId.isBlank() || !byId.containsKey(parentId)) roots.add(session);
                else childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(session);
            }
            for (JsonObject session : roots) {
                String directory = value(session, "directory");
                String project = projectName(directory);
                TreeItem projectItem = projects.computeIfAbsent(project, name -> item(tree, name));
                String folderKey = project + "\n" + directory;
                TreeItem folderItem = folders.computeIfAbsent(folderKey, key -> item(projectItem, directory));
                TreeItem sessionItem = addSessionItem(folderItem, session, false);
                addChildren(sessionItem, session, childrenByParent, new java.util.HashSet<>());
                folderItem.setExpanded(true); projectItem.setExpanded(true);
            }
        } finally {
            tree.setRedraw(true);
        }
        setSummaryMessage(sessions, errors);
        tree.setEnabled(true);
    }

    private void setSummaryMessage(List<JsonObject> sessions, List<String> errors) {
        String suffix = errors.isEmpty() ? "" : " (some roots failed: " + errors.size() + ")";
        message.setText(sessions.isEmpty() ? "No sessions found." + suffix : sessions.size() + " session" + (sessions.size() == 1 ? "" : "s") + suffix);
    }

    /** Cheap fingerprint of the rendered content so quiet background polls (every 5s, see
     * {@link #schedulePoll()}) can skip the full tree rebuild when nothing actually changed. */
    private static String signature(List<JsonObject> sessions, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject session : sessions) {
            sb.append(value(session, "id")).append('\u0001').append(value(session, "parentID")).append('\u0001')
                    .append(value(session, "title")).append('\u0001').append(value(session, "directory")).append('\u0001')
                    .append(time(session)).append('\u0002');
        }
        sb.append('\u0003').append(errors.size());
        return sb.toString();
    }

    private void addChildren(TreeItem parentItem, JsonObject parentSession, Map<String, List<JsonObject>> childrenByParent,
            java.util.Set<String> visiting) {
        String parentId = value(parentSession, "id");
        if (!visiting.add(parentId)) return; // defensive: guard against a malformed parentID cycle
        try {
            List<JsonObject> children = childrenByParent.get(parentId);
            if (children == null) return;
            for (JsonObject child : children) {
                TreeItem childItem = addSessionItem(parentItem, child, true);
                addChildren(childItem, child, childrenByParent, visiting);
            }
            parentItem.setExpanded(true);
        } finally {
            visiting.remove(parentId);
        }
    }

    private TreeItem addSessionItem(TreeItem parent, JsonObject session, boolean isChild) {
        String directory = value(session, "directory");
        String title = value(session, "title");
        if (title.isBlank()) title = "New Session";
        String agent = value(session, "agent");
        String agentLabel = agent.isBlank() ? "" : ChatView.displayName(agent, AgentDescriptions.get(agent));
        String when = time(session);
        String prefix = isChild ? "↳ " : "";
        String label = prefix + title + (!agentLabel.isBlank() ? "  (@" + agentLabel + ")" : "") + "  ·  " + when;
        TreeItem item = item(parent, label);
        item.setData(new SessionTarget(directory, value(session, "id"), title, isChild));
        item.setData("tooltip", title + "\n" + directory + "\n" + when);
        return item;
    }

    private static TreeItem item(Tree parent, String text) { TreeItem i = new TreeItem(parent, SWT.NONE); i.setText(text); return i; }
    private static TreeItem item(TreeItem parent, String text) { TreeItem i = new TreeItem(parent, SWT.NONE); i.setText(text); return i; }

    private void error(Exception ex) {
        if (tree != null && !tree.isDisposed()) {
            message.setText("Couldn’t load sessions: " + message(ex));
            tree.removeAll();
            lastSignature = null;
            tree.setEnabled(true);
        }
    }
    private void ui(Runnable run) { if (!disposed && tree != null && !tree.isDisposed()) tree.getDisplay().asyncExec(() -> { if (!disposed && !tree.isDisposed()) run.run(); }); }

    private List<String> projectRoots() {
        LinkedHashMap<String, String> roots = new LinkedHashMap<>();
        addRoot(roots, effectiveWorkspaceRoot());
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isAccessible() && project.getLocation() != null) addRoot(roots, project.getLocation().toOSString());
        return List.copyOf(roots.values());
    }
    private static void addRoot(Map<String, String> roots, String root) { try { Path p = Path.of(root).toRealPath(); roots.putIfAbsent(p.toString(), p.toString()); } catch (Exception ignored) { } }
    private static String effectiveWorkspaceRoot() { var root = ResourcesPlugin.getWorkspace().getRoot(); String eclipse = root.getLocation() != null ? root.getLocation().toOSString() : System.getProperty("user.dir"); return WorkspaceRoot.resolve(System.getenv("ENV_SCM_WORKSPACE_ROOT"), eclipse); }
    private static String canonical(String path) { try { return Path.of(path).toRealPath().toString(); } catch (Exception e) { return Path.of(path).toAbsolutePath().normalize().toString(); } }
    private static String projectName(String directory) { try { Path p = Path.of(directory); return p.getFileName() == null ? directory : p.getFileName().toString(); } catch (Exception e) { return directory; } }
    private static JsonObject sessionWithDirectory(JsonObject source, String directory) { JsonObject copy = source.deepCopy(); copy.addProperty("directory", directory); return copy; }
    private static String value(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }
    private static String time(JsonObject o) { long n = o.has("time") && o.get("time").isJsonObject() && o.getAsJsonObject("time").has("updated") ? o.getAsJsonObject("time").get("updated").getAsLong() : o.has("updated") ? o.get("updated").getAsLong() : 0; return n == 0 ? "unknown time" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(n)); }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private record SessionTarget(String directory, String id, String title, boolean child) { }
    private record RootResult(Map<String, JsonObject> sessions, String error) { }
    @Override public void setFocus() { if (tree != null) tree.setFocus(); }
    @Override public void dispose() {
        disposed = true;
        INSTANCES.remove(this);
        ChatViewRegistry.removeListener(registryRefresh);
        OpenCodeService service = explorerService;
        if (service != null) new Thread(service::dispose, "opencode-explorer-cleanup").start();
        super.dispose();
    }
}
