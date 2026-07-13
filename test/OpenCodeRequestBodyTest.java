package com.opencode.eclipse.core;

import java.util.List;

import com.google.gson.JsonObject;

public final class OpenCodeRequestBodyTest {
	public static void main(String[] args) {
		JsonObject prompt = OpenCodeService.promptBody("hi", "build", "p/m", "high");
		assert "p".equals(prompt.getAsJsonObject("model").get("providerID").getAsString());
		assert "high".equals(prompt.get("variant").getAsString());
		JsonObject command = OpenCodeService.commandBody("review", "now", "build", "p/m", "high",
				List.of(new FilePartInput("text/plain", "a.txt", "file:///a.txt")));
		assert "p/m".equals(command.get("model").getAsString());
		assert command.getAsJsonArray("parts").size() == 1;
		assert !OpenCodeService.apiKeyBody("secret", null).has("metadata");
		JsonObject metadata = new JsonObject(); metadata.addProperty("region", "eu");
		assert OpenCodeService.apiKeyBody("secret", metadata).getAsJsonObject("metadata").equals(metadata);
		System.out.println("OPENCODE REQUEST BODIES OK");
	}
}
