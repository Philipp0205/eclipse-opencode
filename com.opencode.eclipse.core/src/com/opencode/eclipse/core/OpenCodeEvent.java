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
		var props = raw.getAsJsonObject("properties");
		var status = props != null ? props.getAsJsonObject("status") : null;
		return status != null && status.has("type") && "idle".equals(status.get("type").getAsString());
	}
}
