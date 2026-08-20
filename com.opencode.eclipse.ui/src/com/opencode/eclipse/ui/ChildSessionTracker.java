package com.opencode.eclipse.ui;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Tracks delegated (subagent / task-tool) child sessions across every open {@link ChatView},
 * so the Sessions monitor and Sessions explorer can show a running subagent nested under its
 * parent chat view.
 *
 * <p>State is process-wide (not per-view) because a child session's events are observed by
 * whichever {@link ChatView} happens to be watching, but the row must only ever be attributed
 * to the view that originated the delegation. Callers identify "their" rows with an opaque
 * {@code owner} key (the originating {@link ChatView} instance); this class never calls back
 * into {@code ChatView} and has no SWT dependency, so it is unit-testable on its own.
 */
final class ChildSessionTracker {
	/** A delegated child (subagent) session tracked for the sessions view.
	 * {@code agent} is the built-in or "omo" custom agent name (subagent_type/agent/name
	 * on the task tool call), so both opencode's built-in agents and project-defined
	 * agents under ~/.config/opencode/agent render the same way. {@code directory} is the
	 * owner's working folder captured at delegation time (not read from the owner later,
	 * since it may since have switched to a different session/directory). */
	record Info(String id, String title, String agent, String status, String directory) { }

	// Only running/blocked children are kept — terminal ones are removed immediately so the
	// sessions view never shows a finished subagent as still active. ConcurrentHashMap for
	// safe individual reads/writes; callers still only mutate on the SWT UI thread, since the
	// check-then-act sequences across the two maps are not atomic.
	private static final Map<String, Info> INFO = new ConcurrentHashMap<>();
	private static final Map<String, Object> OWNERS = new ConcurrentHashMap<>();

	private ChildSessionTracker() { }

	/**
	 * A task-tool / subtask part on the current message describes a delegated child session.
	 * Updates the tracked info for its child session id. Returns {@code true} if the tracked
	 * state actually changed (callers use this to decide whether to refresh dependent views).
	 */
	static boolean track(Object owner, JsonObject part, String currentSessionId, String directory) {
		// Search only within "state" (not the whole part): the part's own top-level
		// "sessionID" refers to the *parent* session it belongs to, and would otherwise
		// always be found first, making childId == currentSessionId and short-circuiting
		// every call below. The real child/subagent session id lives nested under
		// state.metadata.sessionId (see OpenCode's task tool output shape).
		JsonObject state = part.getAsJsonObject("state");
		String childId = findSessionId(state);
		if (childId == null || childId.equals(currentSessionId)) return false;
		JsonObject input = state == null ? null : state.getAsJsonObject("input");
		String agent = first(input, "subagent_type", "agent", "name");
		String description = first(input, "description", "task", "prompt");
		String status = mapChildStatus(str(state, "status"));
		Object existingOwner = OWNERS.putIfAbsent(childId, owner);
		if (existingOwner != null && !existingOwner.equals(owner)) return false;
		if (isTerminalChildStatus(status)) {
			return INFO.remove(childId) != null;
		}
		String title = description != null ? description : (agent != null ? agent : childId);
		Info updated = new Info(childId, title, agent != null ? agent : "unknown", status, directory);
		return !updated.equals(INFO.put(childId, updated));
	}

	/** True if {@code sessionId} is a child currently tracked as owned by {@code owner}. */
	static boolean isTrackedBy(Object owner, String sessionId) {
		return sessionId != null && INFO.containsKey(sessionId) && owner.equals(OWNERS.get(sessionId));
	}

	/** Removes a child once its own session reaches idle. Returns {@code true} if it was tracked. */
	static boolean removeIdle(String sessionId) {
		return INFO.remove(sessionId) != null;
	}

	/** Final safety net: keep only children whose id is in {@code stillActiveIds}, among those
	 * owned by {@code owner}. Used after a root turn ends, in case a child's own terminal SSE
	 * event was missed (e.g. it completed after the parent stream already closed). */
	static void retainOnly(Object owner, Set<String> stillActiveIds) {
		INFO.keySet().removeIf(id -> owner.equals(OWNERS.get(id)) && !stillActiveIds.contains(id));
	}

	static List<Info> infoFor(Object owner) {
		return INFO.values().stream().filter(info -> owner.equals(OWNERS.get(info.id()))).toList();
	}

	/** Releases every child owned by {@code owner} (called when its {@link ChatView} disposes). */
	static void releaseOwner(Object owner) {
		OWNERS.entrySet().removeIf(entry -> entry.getValue().equals(owner));
		INFO.keySet().removeIf(id -> !OWNERS.containsKey(id));
	}

	/**
	 * Task/subtask parts identify their delegated session in state.metadata.sessionId
	 * (older servers used sessionID/session_id, sometimes directly on the part).
	 * Search the complete part rather than assuming one particular schema version.
	 */
	static String findSessionId(JsonElement element) {
		if (element == null || element.isJsonNull()) return null;
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				String key = entry.getKey();
				if (("sessionID".equals(key) || "sessionId".equals(key) || "session_id".equals(key))
						&& entry.getValue().isJsonPrimitive()) return entry.getValue().getAsString();
			}
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				String found = findSessionId(entry.getValue());
				if (found != null) return found;
			}
		} else if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				String found = findSessionId(child);
				if (found != null) return found;
			}
		}
		return null;
	}

	static String descendantStatus(JsonObject descendant) {
		String status = statusValue(descendant);
		if (status != null) return mapChildStatus(status);
		return hasCompletedTime(descendant) ? "done" : "running";
	}

	static boolean isTerminalChildStatus(String status) {
		return "done".equals(status) || "error".equals(status) || "cancelled".equals(status);
	}

	static String mapChildStatus(String status) {
		if (status == null) return "running";
		return switch (status.toLowerCase(Locale.ROOT)) {
		case "completed", "done", "success", "finished", "complete", "idle" -> "done";
		case "error", "errored", "failed", "failure" -> "error";
		case "cancelled", "canceled", "aborted" -> "cancelled";
		default -> "running";
		};
	}

	private static String first(JsonObject object, String... keys) {
		if (object == null) return null;
		for (String key : keys) {
			String value = str(object, key);
			if (value != null && !value.isBlank()) return value;
		}
		return null;
	}

	private static boolean hasCompletedTime(JsonObject descendant) {
		if (completedTime(descendant)) return true;
		JsonObject info = descendant == null ? null : descendant.getAsJsonObject("info");
		return completedTime(info);
	}

	private static boolean completedTime(JsonObject object) {
		if (object == null) return false;
		JsonObject time = object.getAsJsonObject("time");
		if (time == null) return false;
		JsonElement completed = time.get("completed");
		return completed != null && !completed.isJsonNull();
	}

	private static String statusValue(JsonObject object) {
		if (object == null) return null;
		JsonElement value = object.get("status");
		if (value != null && !value.isJsonNull()) {
			if (value.isJsonPrimitive()) return value.getAsString();
			if (value.isJsonObject()) {
				JsonObject status = value.getAsJsonObject();
				String result = str(status, "type");
				if (result == null) result = str(status, "state");
				if (result != null) return result;
			}
		}
		JsonObject info = object.getAsJsonObject("info");
		if (info != null && info != object) return statusValue(info);
		return null;
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}
}
