package com.opencode.eclipse.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ProviderAuthPromptsTest {
	public static void main(String[] args) {
		JsonObject answers = new JsonObject(); answers.addProperty("region", "eu");
		JsonObject eq = prompt("eq", "eu"); JsonObject neq = prompt("neq", "us");
		assert ProviderAuthPrompts.applies(eq, answers);
		assert ProviderAuthPrompts.applies(neq, answers);
		answers.remove("region"); assert !ProviderAuthPrompts.applies(eq, answers);
		JsonObject select = JsonParser.parseString("""
				{"options":[{"label":"Europe","value":"eu"},{"label":"USA","value":"us"}]}
				""").getAsJsonObject();
		assert "eu".equals(ProviderAuthPrompts.valueForLabel(select, "Europe"));
		System.out.println("PROVIDER AUTH PROMPTS OK");
	}

	private static JsonObject prompt(String op, String value) {
		JsonObject prompt = new JsonObject(); JsonObject when = new JsonObject();
		when.addProperty("key", "region"); when.addProperty("op", op); when.addProperty("value", value);
		prompt.add("when", when); return prompt;
	}
}
