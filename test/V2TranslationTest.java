package com.opencode.eclipse.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class V2TranslationTest {
	public static void main(String[] args) {
		JsonArray v2 = JsonParser.parseString("""
				[{"id":"msg_u","time":{"created":1},"text":"make a file","type":"user"},
				 {"id":"msg_a","type":"assistant","agent":"build","model":{"id":"m","providerID":"p"},
				  "content":[{"type":"reasoning","text":"thinking"},
				    {"type":"tool","id":"call_1","name":"write","state":{"status":"completed",
				      "input":{"path":"/tmp/x/hello.txt","content":"hi"},
				      "content":[{"type":"text","text":"Created file"}]}},
				    {"type":"tool","id":"call_2","name":"subagent","state":{"status":"streaming","input":{"agent":"explore"}}},
				    {"type":"text","text":"DONE"}],
				  "cost":0.5,"tokens":{"input":10,"output":2,"cache":{"read":1,"write":0}}},
				 {"id":"msg_i","type":"idle","outcome":"succeeded"}]""").getAsJsonArray();
		JsonArray messages = V2.messages(v2, "ses_1");
		assert messages.size() == 2 : messages;
		JsonObject user = messages.get(0).getAsJsonObject();
		assert "user".equals(user.getAsJsonObject("info").get("role").getAsString());
		assert "make a file".equals(user.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
		JsonObject assistant = messages.get(1).getAsJsonObject();
		JsonObject info = assistant.getAsJsonObject("info");
		assert "p".equals(info.get("providerID").getAsString()) && "m".equals(info.get("modelID").getAsString());
		assert info.get("cost").getAsDouble() == 0.5;
		JsonArray parts = assistant.getAsJsonArray("parts");
		assert parts.size() == 4;
		JsonObject tool = parts.get(1).getAsJsonObject();
		assert "write".equals(tool.get("tool").getAsString());
		assert "msg_a".equals(tool.get("messageID").getAsString());
		assert "Created file".equals(tool.getAsJsonObject("state").get("output").getAsString());
		JsonObject task = parts.get(2).getAsJsonObject();
		assert "task".equals(task.get("tool").getAsString());
		assert "pending".equals(task.getAsJsonObject("state").get("status").getAsString());
		assert "explore".equals(task.getAsJsonObject("state").getAsJsonObject("input").get("subagent_type").getAsString());

		JsonObject form = JsonParser.parseString("""
				{"id":"frm_1","sessionID":"ses_1","title":"Questions","fields":[
				  {"key":"q0","title":"Color","description":"Red or blue?","type":"string",
				   "options":[{"value":"r","label":"Red"},{"value":"b","label":"Blue"}],"custom":true},
				  {"key":"q1","title":"Many","type":"multiselect","options":[{"value":"x","label":"X"}]}]}""")
				.getAsJsonObject();
		OpenCodeEvent question = V2.question(form);
		assert "question.asked".equals(question.type());
		JsonArray questions = question.raw().getAsJsonObject("properties").getAsJsonArray("questions");
		assert "Red or blue?".equals(questions.get(0).getAsJsonObject().get("question").getAsString());
		JsonArray answers = JsonParser.parseString("[[\"Blue\"],[\"X\",\"custom\"]]").getAsJsonArray();
		JsonObject answer = V2.formAnswer(questions, answers);
		assert "b".equals(answer.get("q0").getAsString());
		assert answer.getAsJsonArray("q1").size() == 2 && "x".equals(answer.getAsJsonArray("q1").get(0).getAsString());

		JsonObject session = V2.session(JsonParser.parseString("""
				{"id":"ses_1","location":{"directory":"/tmp/p"},"time":{"created":1,"updated":5,"idle":3}}""")
				.getAsJsonObject());
		assert "/tmp/p".equals(session.get("directory").getAsString());
		assert "running".equals(session.get("status").getAsString());

		JsonObject step = JsonParser.parseString("{\"files\":[\"a/b.txt\"]}").getAsJsonObject();
		assert "/tmp/p/a/b.txt".equals(V2.stepFiles(step, "/tmp/p").get(0).getAsString());

		JsonObject providers = V2.providers(JsonParser.parseString("""
				[{"id":"m1","providerID":"p","limit":{"context":100},"variants":[{"id":"high"}]},
				 {"id":"m2","providerID":"p","enabled":false}]""").getAsJsonArray());
		JsonObject provider = providers.getAsJsonArray("providers").get(0).getAsJsonObject();
		assert provider.getAsJsonObject("models").size() == 1;
		assert provider.getAsJsonObject("models").getAsJsonObject("m1").getAsJsonObject("variants").has("high");
		System.out.println("V2 TRANSLATION OK");
	}
}
