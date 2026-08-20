package com.opencode.eclipse.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Converts OpenCode messages and parts to safe, structured conversation HTML. */
final class ConversationHtml {
	private ConversationHtml() {
	}

	static String conversation(JsonArray messages) {
		StringBuilder html = new StringBuilder();
		for (JsonElement element : messages) {
			JsonObject message = element.getAsJsonObject();
			JsonObject info = message.getAsJsonObject("info");
			html.append(message(string(info, "id"), string(info, "role"), message.getAsJsonArray("parts")));
		}
		return html.toString();
	}

	static String message(String id, String role, JsonArray parts) {
		return message(id, role, parts, false);
	}

	static String message(String id, String role, JsonArray parts, boolean expandReasoning) {
		String safeId = id(id != null ? id : "message");
		String cssRole = "user".equals(role) ? "user" : "assistant";
		boolean toolOnly = hasPlainToolPart(parts);
		StringBuilder body = new StringBuilder();
		if (parts != null) {
			for (JsonElement element : parts) {
				body.append(part(element.getAsJsonObject(), expandReasoning, toolOnly));
			}
		}
		if (body.isEmpty()) {
			return "";
		}
		if (toolOnly) {
			return "<div id=\"" + safeId + "\" class=\"tool-log\">" + body + "</div>";
		}
		return "<section id=\"" + safeId + "\" class=\"message " + cssRole + "\">"
				+ "<div class=\"role\">" + ("user".equals(role) ? "You" : "OpenCode") + "</div>"
				+ "<div class=\"content\">" + body + "</div></section>";
	}

	private static boolean hasPlainToolPart(JsonArray parts) {
		if (parts == null) return false;
		boolean plainTool = false;
		for (JsonElement element : parts) {
			JsonObject part = element.getAsJsonObject();
			String type = string(part, "type");
			if ("agent".equals(type) || ("tool".equals(type) && !"task".equals(string(part, "tool")))) {
				plainTool = true;
			}
			if ("text".equals(type) || "reasoning".equals(type)) {
				return false;
			}
		}
		return plainTool;
	}

	static String liveMessage(String id, String role, String markdown) {
		JsonArray parts = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", markdown);
		parts.add(text);
		return message(id, role, parts);
	}

	static String activity(String id) {
		return "<section id=\"" + id(id) + "\" class=\"message assistant\">"
				+ "<div class=\"thinking-live\">Thinking</div></section>";
	}

	private static String part(JsonObject part, boolean expandReasoning, boolean compactTools) {
		return switch (string(part, "type")) {
			case "text" -> markdown(string(part, "text"));
			case "reasoning" -> reasoning(part, expandReasoning);
			case "tool" -> "task".equals(string(part, "tool")) ? taskCard(part) : tool(part, compactTools);
			case "subtask" -> taskCard(part);
			case "agent" -> compactTools ? "<div class=\"tool-line\">agent " + escape(string(part, "name"))
					+ "</div>" : "<div class=\"tool\"><div class=\"tool-head\">Agent · "
					+ escape(string(part, "name")) + "</div></div>";
			default -> "";
		};
	}

	/** Compact, CLI-like disclosure for delegated work. The transcript remains available without
	 * making the main conversation noisy. */
	private static String taskCard(JsonObject part) {
		JsonObject state = part.getAsJsonObject("state");
		JsonObject input = state == null ? null : state.getAsJsonObject("input");
		String name = first(input, "description", "prompt", "name");
		if (name == null) name = first(part, "description", "prompt", "name");
		String agent = first(input, "subagent_type", "agent", "name");
		if (agent == null) agent = first(part, "agent");
		String transcript = first(part, "transcript", "text", "result");
		String status = first(state, "status");
		if (status == null) status = first(part, "status");
		String count = first(part, "toolCount");
		String currentTool = first(part, "currentTool");
		String meta = (count != null ? count : "0") + " tool call" + ("1".equals(count) ? "" : "s");
		return "<details class=\"task-card\"><summary>" + escape(agent != null ? agent : "Task") + " — "
				+ escape(name != null ? name : "delegated work")
				+ "<span class=\"task-meta\">" + meta + "</span></summary><div class=\"task-body\">"
				+ "<div class=\"task-status\">" + escape(status != null ? status : "running")
				+ (currentTool != null ? " · " + escape(currentTool) : "")
				+ (agent != null ? " · " + escape(agent) : "") + "</div>"
				+ (transcript != null ? "<div class=\"subagent-transcript\">" + escape(transcript) + "</div>" : "")
				+ "</div></details>";
	}

	private static String reasoning(JsonObject part, boolean expanded) {
		String text = string(part, "text");
		if (text == null || text.isBlank()) {
			return "";
		}
		return "<details class=\"thinking\"" + (expanded ? " open" : "")
				+ "><summary>Thinking</summary><div class=\"thinking-body\">"
				+ markdown(text) + "</div></details>";
	}

	private static String tool(JsonObject part, boolean compact) {
		String tool = string(part, "tool");
		JsonObject state = part.getAsJsonObject("state");
		JsonObject input = state != null ? state.getAsJsonObject("input") : null;
		String status = string(state, "status");
		String error = string(state, "error");
		String detail = first(input, "filePath", "path", "pattern", "query", "url", "command", "description");
		String skill = "skill".equals(tool) ? first(input, "name") : null;
		if (compact) {
			return "<div class=\"tool-line\">" + escape(tool != null ? tool : "tool")
					+ (skill != null ? " \"" + escape(skill) + "\"" : "")
					+ (detail != null ? " " + escape(detail) : "")
					+ (status != null ? " · " + escape(status) : "")
					+ (error != null ? " <span class=\"tool-error\">" + escape(error) + "</span>" : "")
					+ "</div>";
		}
		return "<div class=\"tool\"><div class=\"tool-head\">" + escape(tool != null ? tool : "tool")
				+ (skill != null ? " \"" + escape(skill) + "\"" : "")
				+ (status != null ? " · " + escape(status) : "") + "</div>"
				+ (detail != null ? "<div class=\"tool-detail\">" + escape(detail) + "</div>" : "")
				+ (error != null ? "<div class=\"tool-error\">" + escape(error) + "</div>" : "") + "</div>";
	}

	private static String markdown(String text) {
		return MarkdownHtml.render(text);
	}

	private static String first(JsonObject object, String... keys) {
		for (String key : keys) {
			String value = string(object, key);
			if (value != null && !value.isBlank()) return value;
		}
		return null;
	}

	private static String string(JsonObject object, String key) {
		return object != null && object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive()
				? object.get(key).getAsString() : null;
	}

	static String escape(String value) {
		if (value == null) return "";
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&#39;");
	}

	static String id(String value) {
		return value.replaceAll("[^A-Za-z0-9_-]", "_");
	}
}
