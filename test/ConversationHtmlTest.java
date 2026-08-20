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
		JsonObject failedTool = part("tool"); failedTool.addProperty("tool", "read");
		JsonObject failedState = new JsonObject(); failedState.addProperty("status", "error");
		failedState.addProperty("error", "Missing key <path>"); failedTool.add("state", failedState); parts.add(failedTool);
		JsonObject skill = part("tool"); skill.addProperty("tool", "skill"); JsonObject skillState = new JsonObject();
		JsonObject skillInput = new JsonObject(); skillInput.addProperty("name", "context7"); skillState.add("input", skillInput);
		skillState.addProperty("status", "completed"); skill.add("state", skillState); parts.add(skill);
		JsonObject subtask = part("subtask");
		subtask.addProperty("agent", "explore"); subtask.addProperty("description", "Inspect source tree");
		JsonObject metadata = new JsonObject(); metadata.addProperty("sessionId", "child-1");
		JsonObject taskState = new JsonObject(); taskState.add("metadata", metadata); subtask.add("state", taskState);
		parts.add(subtask);

		String html = ConversationHtml.message("msg-1", "assistant", parts);
		assert html.contains("<table>") : html;
		assert html.contains("<details class=\"thinking\">") : html;
		assert html.contains("tool-error") && html.contains("Missing key &lt;path&gt;") : html;
		assert html.contains("skill \"context7\" · completed") : html;
		String code = MarkdownHtml.render("```java\nclass A {}\n```");
		assert code.contains("class=\"language-java\"") && code.contains("hl-keyword") : code;
		String indentedCode = MarkdownHtml.render("  ```java\nif (a < b) { **not markdown**; }\n  ```");
		assert indentedCode.contains("<pre><code class=\"language-java\">") : indentedCode;
		assert indentedCode.contains("&lt;") && indentedCode.contains("**not markdown**") : indentedCode;
		assert !indentedCode.contains("<strong>") : indentedCode;
		assert html.contains("explore") && html.contains("Inspect source tree") && html.contains("tool calls") : html;
		assert html.contains("class=\"task-status\">running") : html;
		// The task schema stores the delegated child in state.metadata.sessionId;
		// terminal synchronization writes the same state.status consumed by the card.
		assert metadata.get("sessionId").getAsString().equals("child-1");
		taskState.addProperty("status", "done");
		assert ConversationHtml.message("msg-1", "assistant", parts).contains("class=\"task-status\">done") : html;
		assert !html.contains("<script>") && html.contains("&lt;script&gt;") : html;
		System.out.println("CONVERSATION HTML OK");
	}

	private static JsonObject part(String type) {
		JsonObject object = new JsonObject(); object.addProperty("type", type); return object;
	}
}
