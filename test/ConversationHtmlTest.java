package com.opencode.eclipse.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ConversationHtmlTest {
	public static void main(String[] args) {
		JsonArray parts = new JsonArray();
		JsonObject text = part("text");
		text.addProperty("text", "| A | B |\n|---|---|\n| 1 | 2 |\n\n<script>alert(1)</script>");
		parts.add(text);
		JsonObject reasoning = part("reasoning");
		reasoning.addProperty("text", "**Inspecting** the code");
		parts.add(reasoning);
		JsonObject todoTool = part("tool");
		todoTool.addProperty("tool", "todowrite");
		JsonObject state = new JsonObject();
		JsonObject input = new JsonObject();
		JsonArray todos = new JsonArray();
		todos.add(todo("Implement renderer", "in_progress", "high"));
		todos.add(todo("Run tests", "completed", "medium"));
		todos.add(todo("Old task", "cancelled", "low"));
		input.add("todos", todos);
		state.add("input", input);
		todoTool.add("state", state);
		parts.add(todoTool);
		JsonObject failedTool = part("tool"); failedTool.addProperty("tool", "todowrite");
		JsonObject failedState = new JsonObject(); failedState.addProperty("status", "error");
		failedState.addProperty("error", "Missing key <todos>"); failedTool.add("state", failedState); parts.add(failedTool);
		JsonObject skill = part("tool"); skill.addProperty("tool", "skill"); JsonObject skillState = new JsonObject();
		JsonObject skillInput = new JsonObject(); skillInput.addProperty("name", "context7"); skillState.add("input", skillInput);
		skillState.addProperty("status", "completed"); skill.add("state", skillState); parts.add(skill);
		JsonObject subtask = part("subtask");
		subtask.addProperty("agent", "explore"); subtask.addProperty("description", "Inspect source tree");
		parts.add(subtask);

		String html = ConversationHtml.message("msg-1", "assistant", parts);
		assert html.contains("<table>") : html;
		assert html.contains("<details class=\"thinking\">") : html;
		assert html.contains("Implement renderer") && html.contains("Run tests") : html;
		assert html.contains("todo done") : html;
		assert html.contains("todo current") && html.contains("Current") : html;
		assert html.contains("1 / 2 completed") && html.contains("todo cancelled") : html;
		assert html.contains("tool-error") && html.contains("Missing key &lt;todos&gt;") : html;
		assert html.contains("skill \"context7\" · completed") : html;
		String code = MarkdownHtml.render("```java\nclass A {}\n```");
		assert code.contains("class=\"language-java\"") && code.contains("hl-keyword") : code;
		assert html.contains("explore") && html.contains("Inspect source tree") && html.contains("tool calls") : html;
		assert !html.contains("<script>") && html.contains("&lt;script&gt;") : html;
		System.out.println("CONVERSATION HTML OK");
	}

	private static JsonObject part(String type) {
		JsonObject object = new JsonObject(); object.addProperty("type", type); return object;
	}

	private static JsonObject todo(String content, String status, String priority) {
		JsonObject object = new JsonObject();
		object.addProperty("content", content); object.addProperty("status", status);
		object.addProperty("priority", priority); return object;
	}
}
