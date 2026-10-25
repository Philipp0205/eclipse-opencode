package com.opencode.eclipse.core;

import java.util.List;

import com.google.gson.JsonObject;

public final class OpenCodeRequestBodyTest {
	public static void main(String[] args) {
		JsonObject prompt = OpenCodeService.promptBody("hi",
				List.of(new FilePartInput("text/plain", "a.txt", "file:///a.txt")), List.of("review"));
		assert "hi".equals(prompt.get("text").getAsString());
		assert "file:///a.txt".equals(prompt.getAsJsonArray("files").get(0).getAsJsonObject().get("uri").getAsString());
		assert "review".equals(prompt.getAsJsonArray("skills").get(0).getAsJsonObject().get("id").getAsString());
		JsonObject command = OpenCodeService.commandBody("review", "now",
				List.of(new FilePartInput("text/plain", "a.txt", "file:///a.txt")));
		assert "review".equals(command.get("name").getAsString());
		assert "now".equals(command.get("text").getAsString());
		assert command.getAsJsonArray("files").size() == 1;
		assert !OpenCodeService.apiKeyBody("secret", null).has("answer");
		JsonObject metadata = new JsonObject(); metadata.addProperty("region", "eu");
		assert OpenCodeService.apiKeyBody("secret", metadata).getAsJsonObject("answer").equals(metadata);
		assert "/tmp/destination".equals(OpenCodeService.moveSessionBody("/tmp/destination").get("directory").getAsString());
		JsonObject ref = V2.modelRef("p/m", "high");
		assert "p".equals(ref.get("providerID").getAsString()) && "m".equals(ref.get("id").getAsString());
		assert "high".equals(ref.get("variant").getAsString());
		assert OpenCodeService.withLocation("/api/agent", "/a b").equals("/api/agent?location%5Bdirectory%5D=%2Fa+b");
		System.out.println("OPENCODE REQUEST BODIES OK");
	}
}
