package com.opencode.eclipse.core;

/** Minimal event carrier for the SSE stream. {@code raw} is the parsed JSON object. */
public record OpenCodeEvent(String type, com.google.gson.JsonObject raw) {

	/** sessionID from event.properties, or null. */
	public String sessionID() {
		var props = raw.getAsJsonObject("properties");
		if (props == null) return null;
		if (props.has("sessionID")) return props.get("sessionID").getAsString();
		var info = props.getAsJsonObject("info");
		if (info != null && info.has("sessionID")) return info.get("sessionID").getAsString();
		var part = props.getAsJsonObject("part");
		if (part != null && part.has("sessionID")) return part.get("sessionID").getAsString();
		// Only session events carry a session object whose id is the session id.
		return isSessionEvent() && info != null && info.has("id") ? info.get("id").getAsString() : null;
	}

	private boolean isSessionEvent() {
		return type.startsWith("session.");
	}

	/** Current session completion event, with deprecated session.idle fallback. */
	public boolean isIdle() {
		if ("session.idle".equals(type)) return true;
		if (!"session.status".equals(type)) return false;
		return isCompletionStatus(status());
	}

	/** Status type carried by session status events, when present. */
	public String status() {
		var props = raw.getAsJsonObject("properties");
		String status = statusValue(props);
		if (status == null) status = statusValue(raw);
		return status != null ? status : ("session.idle".equals(type) ? "idle" : null);
	}

	private static String statusValue(com.google.gson.JsonObject object) {
		if (object == null) return null;
		var value = object.get("status");
		if (value != null && !value.isJsonNull()) {
			if (value.isJsonPrimitive()) return value.getAsString();
			if (value.isJsonObject()) {
				var status = value.getAsJsonObject();
				for (String key : new String[] { "type", "state" }) {
					if (status.has(key) && status.get(key).isJsonPrimitive()) return status.get(key).getAsString();
				}
			}
		}
		var info = object.getAsJsonObject("info");
		if (info != null && info != object) return statusValue(info);
		return null;
	}

	private static boolean isCompletionStatus(String status) {
		if (status == null) return false;
		return switch (status.toLowerCase(java.util.Locale.ROOT)) {
		case "idle", "done", "success", "finished", "complete", "completed", "reconciled", "terminated" -> true;
		default -> false;
		};
	}
}
