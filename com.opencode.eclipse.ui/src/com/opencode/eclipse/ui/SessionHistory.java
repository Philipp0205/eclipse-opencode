package com.opencode.eclipse.ui;

import java.nio.file.Path;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;
import com.opencode.eclipse.core.OpenCodeService;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

/** Shared, cross-project fetch of sessions known to the local OpenCode server (including sessions
 * with no currently-open ChatView), used by both {@link SessionsExplorerView} and
 * {@link SessionMonitorView}. Read-only: does not mutate any {@link OpenCodeService}'s active
 * session/workspace state. */
final class SessionHistory {
    private SessionHistory() { }

    record FetchResult(Map<String, JsonObject> sessions, List<String> errors) { }

    /** Query {@code service} across the Eclipse workspace root plus every open project's
     * location, merging results by {@code id+"\n"+directory} (first root to answer wins). */
    static FetchResult fetchAll(OpenCodeService service) {
        List<String> roots = projectRoots();
        Map<String, JsonObject> sessions = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int parallelism = Math.max(1, Math.min(4, roots.size()));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            CompletionService<RootResult> completed = new ExecutorCompletionService<>(pool);
            for (String root : roots) completed.submit(() -> listRoot(service, root));
            for (int i = 0; i < roots.size(); i++) {
                RootResult result;
                try {
                    result = completed.take().get();
                } catch (Exception ex) {
                    errors.add(message(ex));
                    continue;
                }
                result.sessions().forEach((key, session) -> sessions.putIfAbsent(key, session));
                if (result.error() != null) errors.add(result.error());
            }
        } finally {
            pool.shutdownNow();
        }
        return new FetchResult(sessions, errors);
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

    static List<String> projectRoots() {
        LinkedHashMap<String, String> roots = new LinkedHashMap<>();
        addRoot(roots, effectiveWorkspaceRoot());
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isAccessible() && project.getLocation() != null) addRoot(roots, project.getLocation().toOSString());
        return List.copyOf(roots.values());
    }

    private static void addRoot(Map<String, String> roots, String root) {
        try {
            Path p = Path.of(root).toRealPath();
            roots.putIfAbsent(p.toString(), p.toString());
        } catch (Exception ignored) { }
    }

    static String effectiveWorkspaceRoot() {
        var root = ResourcesPlugin.getWorkspace().getRoot();
        String eclipse = root.getLocation() != null ? root.getLocation().toOSString() : System.getProperty("user.dir");
        return WorkspaceRoot.resolve(System.getenv("ENV_SCM_WORKSPACE_ROOT"), eclipse);
    }

    private static String canonical(String path) {
        try {
            return Path.of(path).toRealPath().toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }

    private static JsonObject sessionWithDirectory(JsonObject source, String directory) {
        JsonObject copy = source.deepCopy();
        copy.addProperty("directory", directory);
        return copy;
    }

    static String value(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    static String time(JsonObject o) {
        long n = o.has("time") && o.get("time").isJsonObject() && o.getAsJsonObject("time").has("updated")
                ? o.getAsJsonObject("time").get("updated").getAsLong()
                : o.has("updated") ? o.get("updated").getAsLong() : 0;
        return n == 0 ? "unknown time" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(n));
    }

    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private record RootResult(Map<String, JsonObject> sessions, String error) { }
}
