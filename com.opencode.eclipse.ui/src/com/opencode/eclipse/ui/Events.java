package com.opencode.eclipse.ui;

import com.google.gson.JsonObject;

/**
 * UI-side event decoding for the opencode SSE stream. Pure functions over the
 * event JSON so {@link ChatView} stays about widgets, not JSON shapes.
 */
final class Events {

	private Events() {
	}

	static JsonObject props(JsonObject event) {
		return event.getAsJsonObject("properties");
	}

	static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	/** message.updated -> role (user/assistant), or null. */
	static String messageRole(JsonObject event) {
		JsonObject p = props(event);
		if (p == null) {
			return null;
		}
		JsonObject info = p.getAsJsonObject("info");
		return info != null ? str(info, "role") : null;
	}

	static String messageId(JsonObject event) {
		JsonObject p = props(event);
		if (p == null) {
			return null;
		}
		JsonObject info = p.getAsJsonObject("info");
		return info != null ? str(info, "id") : null;
	}

	/** message.part.updated -> the part object, or null. */
	static JsonObject part(JsonObject event) {
		JsonObject p = props(event);
		return p != null ? p.getAsJsonObject("part") : null;
	}

	/** session.error -> a human-readable message, or null. */
	static String errorMessage(JsonObject event) {		JsonObject p = props(event);
		if (p == null) {
			return null;
		}
		JsonObject error = p.getAsJsonObject("error");
		if (error == null) {
			return null;
		}
		JsonObject data = error.getAsJsonObject("data");
		String msg = data != null ? str(data, "message") : null;
		return msg != null ? msg : str(error, "name");
	}

	/** file.edited -> the edited file path, or null. */
	static String editedFile(JsonObject event) {
		JsonObject p = props(event);
		return p != null ? str(p, "file") : null;
	}
}
