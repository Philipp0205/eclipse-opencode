package com.opencode.eclipse.core;

/** Minimal event carrier for the SSE stream. {@code raw} is the parsed JSON object. */
public record OpenCodeEvent(String type, com.google.gson.JsonObject raw) {

	/** sessionID from event.properties, or null. */
	public String sessionID() {
		var props = raw.getAsJsonObject("properties");
		if (props == null) return null;
		if (props.has("sessionID")) return props.get("sessionID").getAsString();
		var info = props.getAsJsonObject("info");
		return info != null && info.has("id") ? info.get("id").getAsString() : null;
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
