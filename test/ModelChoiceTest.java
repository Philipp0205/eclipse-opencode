package com.opencode.eclipse.ui;

import com.google.gson.JsonParser;

public final class ModelChoiceTest {
	public static void main(String[] args) {
		var providers = JsonParser.parseString("""
				{"providers":[{"id":"p","models":{"m":{"limit":{"context":42},"variants":{
				"low":{},"off":{"disabled":true}}}}}]}
				""").getAsJsonObject();
		var choices = ModelChoice.from(providers);
		assert choices.size() == 2 : choices;
		assert choices.get(0).model().equals("p/m") && choices.get(0).variant() == null;
		assert choices.get(1).label().equals("p/m [low]") && choices.get(1).contextLimit() == 42;
		System.out.println("MODEL CHOICE OK");
	}
}
