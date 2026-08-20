package com.opencode.eclipse.core;

import java.util.HashSet;

import com.google.gson.JsonParser;

public final class OpenCodeEventTest {
	public static void main(String[] args) {
		assert "root".equals(event("message.part", "{\"part\":{\"sessionID\":\"root\"}}").sessionID());
		assert "child".equals(event("message.updated", "{\"info\":{\"sessionID\":\"child\"}}").sessionID());
		assert "created".equals(event("session.created", "{\"info\":{\"id\":\"created\"}}").sessionID());
		assert event("message.updated", "{\"info\":{\"id\":\"message\"}}").sessionID() == null;

		var children = new HashSet<String>();
		children.add("child");
		assert OpenCodeService.isForwardableEvent(event("message.part", "{\"sessionID\":\"child\"}"),
				"root", children);
		assert !OpenCodeService.isForwardableEvent(event("session.status", "{}"), "root", children);
		assert OpenCodeService.isForwardableEvent(event("file.edited", "{}"), "root", children);
		assert !OpenCodeService.isForwardableEvent(event("message.updated", "{}"), "root", children);
		assert "finished".equals(event("session.status", "{\"status\":\"finished\"}").status());
		assert event("session.status", "{\"status\":{\"state\":\"completed\"}}").isIdle();
		assert !event("session.status", "{\"status\":\"mysterious\"}").isIdle();
		System.out.println("OPENCODE EVENTS OK");
	}

	private static OpenCodeEvent event(String type, String properties) {
		var raw = JsonParser.parseString("{\"type\":\"" + type + "\",\"properties\":"
				+ properties + "}").getAsJsonObject();
		return new OpenCodeEvent(type, raw);
	}
}
