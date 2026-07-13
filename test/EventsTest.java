package com.opencode.eclipse.ui;

import com.google.gson.JsonParser;

public final class EventsTest {
	public static void main(String[] args) {
		var completed = JsonParser.parseString("""
				{"type":"tool","tool":"todowrite","state":{"status":"completed","input":{"todos":[
				{"content":"First","status":"in_progress","priority":"high"}]}}}
				""").getAsJsonObject();
		assert Events.todos(completed).size() == 1;
		var metadata = JsonParser.parseString("""
				{"type":"tool","tool":"todoread","state":{"status":"completed","metadata":{"todos":[]}}}
				""").getAsJsonObject();
		assert Events.todos(metadata).isEmpty();
		var failed = JsonParser.parseString("""
				{"type":"tool","tool":"todowrite","state":{"status":"error","input":{"todos":[]}}}
				""").getAsJsonObject();
		assert Events.todos(failed) == null;
		System.out.println("EVENTS OK");
	}
}
