package com.opencode.eclipse.ui;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

record ModelChoice(String model, String variant, long contextLimit) {
	/** Compact label used for the selected model in the chat toolbar. */
	String compactLabel() {
		String display = model;
		int slash = display.indexOf('/');
		if (slash >= 0 && slash + 1 < display.length()) display = display.substring(slash + 1);
		return variant == null ? display : display + " [" + variant + "]";
	}

	/** Label used in the picker, including the provider for disambiguation. */
	String popupLabel() {
		String display = model.replaceFirst("/", " / ");
		return variant == null ? display : display + " [" + variant + "]";
	}

	static List<ModelChoice> from(JsonObject providers) {
		List<ModelChoice> result = new ArrayList<>();
		JsonArray items = providers.getAsJsonArray("providers");
		if (items == null) return result;
		for (JsonElement element : items) {
			JsonObject provider = element.getAsJsonObject();
			JsonObject models = provider.getAsJsonObject("models");
			if (models == null) continue;
			for (String id : models.keySet()) {
				JsonObject metadata = models.getAsJsonObject(id);
				JsonObject limit = metadata.getAsJsonObject("limit");
				long context = limit != null && limit.has("context") ? limit.get("context").getAsLong() : 0;
				String model = provider.get("id").getAsString() + "/" + id;
				result.add(new ModelChoice(model, null, context));
				JsonObject variants = metadata.getAsJsonObject("variants");
				if (variants != null) for (String variant : variants.keySet()) {
					JsonElement value = variants.get(variant);
					boolean disabled = value.isJsonObject() && value.getAsJsonObject().has("disabled")
							&& value.getAsJsonObject().get("disabled").getAsBoolean();
					if (!disabled) result.add(new ModelChoice(model, variant, context));
				}
			}
		}
		return List.copyOf(result);
	}
}
