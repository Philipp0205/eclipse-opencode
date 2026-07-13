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
		StringBuilder body = new StringBuilder();
		if (parts != null) {
			for (JsonElement element : parts) {
				body.append(part(element.getAsJsonObject(), expandReasoning));
			}
		}
		if (body.isEmpty()) {
			return "";
		}
		return "<section id=\"" + safeId + "\" class=\"message " + cssRole + "\">"
				+ "<div class=\"role\">" + ("user".equals(role) ? "You" : "OpenCode") + "</div>"
				+ "<div class=\"content\">" + body + "</div></section>";
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

	static String todos(String id, JsonArray todos) {
		JsonObject part = new JsonObject(); part.addProperty("type", "tool"); part.addProperty("tool", "todowrite");
		JsonObject state = new JsonObject(); JsonObject input = new JsonObject(); input.add("todos", todos);
		state.add("input", input); part.add("state", state);
		return "<section id=\"" + id(id) + "\" class=\"current-todos\" aria-label=\"Current task progress\"><div class=\"content\">"
				+ tool(part) + "</div></section>";
	}

	private static String part(JsonObject part, boolean expandReasoning) {
		return switch (string(part, "type")) {
			case "text" -> markdown(string(part, "text"));
			case "reasoning" -> reasoning(part, expandReasoning);
			case "tool" -> tool(part);
			case "subtask" -> "<div class=\"tool\"><div class=\"tool-head\">Subagent · "
					+ escape(string(part, "agent")) + "</div><div class=\"tool-detail\">"
					+ escape(first(part, "description", "prompt")) + "</div></div>";
			case "agent" -> "<div class=\"tool\"><div class=\"tool-head\">Agent · "
					+ escape(string(part, "name")) + "</div></div>";
			default -> "";
		};
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

	private static String tool(JsonObject part) {
		String tool = string(part, "tool");
		JsonObject state = part.getAsJsonObject("state");
		JsonObject input = state != null ? state.getAsJsonObject("input") : null;
		String status = string(state, "status");
		String error = string(state, "error");
		if (("todowrite".equals(tool) || "todoread".equals(tool)) && error == null) {
			return todos(part, state, input);
		}
		String detail = first(input, "filePath", "path", "pattern", "query", "url", "command", "description");
		return "<div class=\"tool\"><div class=\"tool-head\">" + escape(tool != null ? tool : "tool")
				+ (status != null ? " · " + escape(status) : "") + "</div>"
				+ (detail != null ? "<div class=\"tool-detail\">" + escape(detail) + "</div>" : "")
				+ (error != null ? "<div class=\"tool-error\">" + escape(error) + "</div>" : "") + "</div>";
	}

	private static String todos(JsonObject part, JsonObject state, JsonObject input) {
		JsonArray todos = input != null ? input.getAsJsonArray("todos") : null;
		JsonObject metadata = state != null ? state.getAsJsonObject("metadata") : null;
		if (todos == null && metadata != null) {
			todos = metadata.getAsJsonArray("todos");
		}
		StringBuilder rows = new StringBuilder();
		int completed = 0; int actionable = 0;
		if (todos != null) {
			for (JsonElement element : todos) {
				JsonObject todo = element.getAsJsonObject();
				String status = string(todo, "status");
				boolean done = "completed".equals(status);
				boolean current = "in_progress".equals(status); boolean cancelled = "cancelled".equals(status);
				if (!cancelled) actionable++; if (done) completed++;
				String icon = done ? "✓" : (current ? "●" : (cancelled ? "×" : "○"));
				rows.append("<li class=\"todo ").append(done ? "done" : current ? "current" : cancelled ? "cancelled" : "")
						.append("\"><span class=\"todo-status\">")
						.append(icon).append("</span><span class=\"todo-text\">").append(escape(string(todo, "content")))
						.append(current ? " <strong>Current</strong>" : "").append("</span><span class=\"priority\">")
						.append(escape(string(todo, "priority")))
						.append("</span></li>");
			}
		}
		return "<details class=\"tool\" open><summary class=\"tool-head\">Todos"
				+ (todos != null ? " · " + completed + " / " + actionable + " completed" : "")
				+ "</summary><ul class=\"todos\">" + rows + "</ul></details>";
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
