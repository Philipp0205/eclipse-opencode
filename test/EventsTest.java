package com.opencode.eclipse.ui;

import com.google.gson.JsonParser;

public final class EventsTest {
	public static void main(String[] args) {
		var message = JsonParser.parseString("""
				{"type":"message.updated","properties":{"info":{"id":"msg_1","role":"assistant"}}}
				""").getAsJsonObject();
		assert "assistant".equals(Events.messageRole(message));
		assert "msg_1".equals(Events.messageId(message));

		var partEvent = JsonParser.parseString("""
				{"type":"message.part.updated","properties":{"part":{"type":"text","text":"hi"}}}
				""").getAsJsonObject();
		assert "text".equals(Events.str(Events.part(partEvent), "type"));

		var empty = JsonParser.parseString("{\"type\":\"message.updated\",\"properties\":{}}").getAsJsonObject();
		assert Events.messageRole(empty) == null && Events.messageId(empty) == null && Events.part(empty) == null;

		var failure = JsonParser.parseString("""
				{"type":"session.error","properties":{"error":{"name":"ProviderError","data":{"message":"boom"}}}}
				""").getAsJsonObject();
		assert "boom".equals(Events.errorMessage(failure));
		var nameOnly = JsonParser.parseString("""
				{"type":"session.error","properties":{"error":{"name":"ProviderError"}}}
				""").getAsJsonObject();
		assert "ProviderError".equals(Events.errorMessage(nameOnly));

		var edited = JsonParser.parseString("""
				{"type":"file.edited","properties":{"file":"/tmp/a.txt"}}
				""").getAsJsonObject();
		assert "/tmp/a.txt".equals(Events.editedFile(edited));
		System.out.println("EVENTS OK");
	}
}
