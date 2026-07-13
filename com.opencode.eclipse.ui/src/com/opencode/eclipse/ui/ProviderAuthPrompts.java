package com.opencode.eclipse.ui;

import com.google.gson.JsonObject;

final class ProviderAuthPrompts {
	private ProviderAuthPrompts() { }

	static boolean applies(JsonObject prompt, JsonObject answers) {
		JsonObject when = prompt.getAsJsonObject("when");
		if (when == null) return true;
		String key = Events.str(when, "key");
		if (key == null || !answers.has(key)) return false;
		boolean equal = answers.get(key).getAsString().equals(Events.str(when, "value"));
		return "neq".equals(Events.str(when, "op")) ? !equal : equal;
	}

	static String valueForLabel(JsonObject prompt, String label) {
		for (var option : prompt.getAsJsonArray("options")) {
			JsonObject value = option.getAsJsonObject();
			if (label.equals(Events.str(value, "label"))) return Events.str(value, "value");
		}
		return null;
	}
}
