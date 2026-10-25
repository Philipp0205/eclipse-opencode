package com.opencode.eclipse.core;

import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Translates OpenCode V2 server payloads into the message/part/event shapes the Eclipse UI renders.
 *
 * <p>The V2 API stores assistant output as a {@code content} array on each message and streams
 * fine-grained {@code session.*} events. The UI works with {@code {info, parts}} messages and
 * {@code message.updated}/{@code message.part.updated} events, so all conversion lives here.
 */
final class V2 {
	private V2() { }

	/** Unwrap the V2 {@code {location?, data}} envelope. */
	static JsonElement data(JsonElement response) {
		if (response != null && response.isJsonObject() && response.getAsJsonObject().has("data"))
			return response.getAsJsonObject().get("data");
		return response;
	}

	/** V2 Session.Info to the session shape used by the UI (adds directory, parentID, status). */
	static JsonObject session(JsonObject s) {
		JsonObject out = s.deepCopy();
		JsonObject location = s.getAsJsonObject("location");
		String directory = str(location, "directory");
		if (directory != null) out.addProperty("directory", directory);
		if (!out.has("title") || out.get("title").isJsonNull()) out.addProperty("title", "New Session");
		out.addProperty("status", sessionStatus(s));
		return out;
	}

	static JsonArray sessions(JsonElement list) {
		JsonArray out = new JsonArray();
		JsonElement data = data(list);
		if (data != null && data.isJsonArray()) for (JsonElement e : data.getAsJsonArray())
			if (e.isJsonObject()) out.add(session(e.getAsJsonObject()));
		return out;
	}

	/** "running" while the session has work newer than its last idle point, else its outcome. */
	static String sessionStatus(JsonObject s) {
		JsonObject time = s.getAsJsonObject("time");
		double idle = num(time, "idle"), updated = num(time, "updated");
		if (idle <= 0 || updated > idle) return "running";
		String outcome = str(s, "outcome");
		if ("failed".equals(outcome)) return "error";
		if ("interrupted".equals(outcome)) return "cancelled";
		return "idle";
	}

	/** V2 message to {@code {info, parts}}, or null for non-conversation entries. */
	static JsonObject message(JsonObject m, String sessionId) {
		String type = str(m, "type");
		String id = str(m, "id");
		if (id == null) return null;
		JsonObject info = new JsonObject();
		info.addProperty("id", id);
		if (sessionId != null) info.addProperty("sessionID", sessionId);
		if (m.has("time")) info.add("time", m.get("time"));
		JsonArray parts = new JsonArray();
		switch (type == null ? "" : type) {
		case "user" -> {
			info.addProperty("role", "user");
			String text = str(m, "text");
			if (text == null) text = str(m.getAsJsonObject("payload"), "text");
			parts.add(textPart(id + "-0", id, sessionId, "text", text != null ? text : ""));
		}
		case "synthetic" -> {
			info.addProperty("role", "user");
			parts.add(textPart(id + "-0", id, sessionId, "text", str(m, "text")));
		}
		case "assistant" -> {
			info.addProperty("role", "assistant");
			copy(m, info, "agent", "cost", "tokens", "finish", "error");
			JsonObject model = m.getAsJsonObject("model");
			if (model != null) {
				info.addProperty("providerID", str(model, "providerID"));
				info.addProperty("modelID", str(model, "id"));
			}
			JsonArray content = m.getAsJsonArray("content");
			if (content != null) for (int i = 0; i < content.size(); i++) {
				JsonObject part = part(content.get(i).getAsJsonObject(), id + "-" + i, id, sessionId);
				if (part != null) parts.add(part);
			}
		}
		case "shell" -> {
			info.addProperty("role", "assistant");
			JsonObject part = new JsonObject();
			part.addProperty("id", id + "-0"); part.addProperty("messageID", id);
			part.addProperty("type", "tool"); part.addProperty("tool", "shell");
			JsonObject state = new JsonObject();
			String status = str(m, "status");
			state.addProperty("status", "running".equals(status) ? "running" : "completed");
			JsonObject input = new JsonObject(); input.addProperty("command", str(m, "command"));
			state.add("input", input);
			part.add("state", state);
			parts.add(part);
		}
		default -> { return null; }
		}
		JsonObject out = new JsonObject();
		out.add("info", info);
		out.add("parts", parts);
		return out;
	}

	static JsonArray messages(JsonArray v2, String sessionId) {
		JsonArray out = new JsonArray();
		for (JsonElement e : v2) {
			if (!e.isJsonObject()) continue;
			JsonObject message = message(e.getAsJsonObject(), sessionId);
			if (message != null) out.add(message);
		}
		return out;
	}

	private static JsonObject part(JsonObject c, String fallbackId, String messageId, String sessionId) {
		String type = str(c, "type");
		if ("text".equals(type) || "reasoning".equals(type))
			return textPart(fallbackId, messageId, sessionId, type, str(c, "text"));
		if (!"tool".equals(type)) return null;
		JsonObject part = new JsonObject();
		String callId = str(c, "id");
		part.addProperty("id", callId != null ? callId : fallbackId);
		part.addProperty("callID", callId);
		part.addProperty("messageID", messageId);
		if (sessionId != null) part.addProperty("sessionID", sessionId);
		part.addProperty("type", "tool");
		String name = str(c, "name");
		part.addProperty("tool", "subagent".equals(name) ? "task" : name);
		JsonObject v2state = c.getAsJsonObject("state");
		JsonObject state = new JsonObject();
		String status = str(v2state, "status");
		state.addProperty("status", "streaming".equals(status) ? "pending" : status);
		JsonElement input = v2state == null ? null : v2state.get("input");
		JsonObject inputObject = input != null && input.isJsonObject() ? input.getAsJsonObject().deepCopy() : new JsonObject();
		if (!inputObject.has("subagent_type") && inputObject.has("agent")) inputObject.add("subagent_type", inputObject.get("agent"));
		state.add("input", inputObject);
		String output = contentText(v2state == null ? null : v2state.getAsJsonArray("content"));
		if (output != null) state.addProperty("output", output);
		JsonObject error = v2state == null ? null : v2state.getAsJsonObject("error");
		if (error != null) state.addProperty("error", str(error, "message"));
		if (v2state != null && v2state.has("metadata")) state.add("metadata", v2state.get("metadata"));
		if (c.has("time")) state.add("time", c.get("time"));
		part.add("state", state);
		return part;
	}

	private static JsonObject textPart(String id, String messageId, String sessionId, String type, String text) {
		JsonObject part = new JsonObject();
		part.addProperty("id", id);
		part.addProperty("messageID", messageId);
		if (sessionId != null) part.addProperty("sessionID", sessionId);
		part.addProperty("type", type);
		part.addProperty("text", text != null ? text : "");
		return part;
	}

	private static String contentText(JsonArray content) {
		if (content == null) return null;
		StringBuilder sb = new StringBuilder();
		for (JsonElement e : content) {
			if (e.isJsonObject() && "text".equals(str(e.getAsJsonObject(), "type"))) {
				if (!sb.isEmpty()) sb.append('\n');
				sb.append(str(e.getAsJsonObject(), "text"));
			}
		}
		return sb.isEmpty() ? null : sb.toString();
	}

	/** Replace stored text/reasoning parts with longer streamed text for the same kind and ordinal. */
	static void overlayStreamedText(JsonArray parts, java.util.Map<String, StringBuilder> streamed) {
		java.util.Map<String, Integer> ordinals = new java.util.HashMap<>();
		for (JsonElement e : parts) {
			JsonObject part = e.getAsJsonObject();
			String type = str(part, "type");
			if (!"text".equals(type) && !"reasoning".equals(type)) continue;
			int ordinal = ordinals.merge(type, 1, Integer::sum) - 1;
			StringBuilder text = streamed.get(type + ":" + ordinal);
			String stored = str(part, "text");
			if (text != null && (stored == null || stored.length() < text.length())) part.addProperty("text", text.toString());
		}
	}

	// ---- events ------------------------------------------------------------

	/** Session ID carried by a raw V2 event, or null. */
	static String eventSession(JsonObject raw) {
		JsonObject data = raw.getAsJsonObject("data");
		String sid = str(data, "sessionID");
		if (sid == null) sid = str(data == null ? null : data.getAsJsonObject("form"), "sessionID");
		return sid;
	}

	static OpenCodeEvent event(String type, JsonObject properties) {
		JsonObject raw = new JsonObject();
		raw.addProperty("type", type);
		raw.add("properties", properties);
		return new OpenCodeEvent(type, raw);
	}

	static OpenCodeEvent status(String sessionId, String status) {
		JsonObject p = new JsonObject();
		p.addProperty("sessionID", sessionId);
		JsonObject s = new JsonObject(); s.addProperty("type", status);
		p.add("status", s);
		return event("session.status", p);
	}

	static OpenCodeEvent error(String sessionId, String message) {
		JsonObject p = new JsonObject();
		p.addProperty("sessionID", sessionId);
		JsonObject error = new JsonObject(); error.addProperty("name", "Error");
		JsonObject data = new JsonObject(); data.addProperty("message", message);
		error.add("data", data);
		p.add("error", error);
		return event("session.error", p);
	}

	/** V2 permission request (event data or list entry) to the permission.asked shape. */
	static OpenCodeEvent permission(JsonObject request, String directory) {
		JsonObject p = new JsonObject();
		p.addProperty("id", str(request, "id"));
		p.addProperty("sessionID", str(request, "sessionID"));
		p.addProperty("permission", str(request, "action"));
		JsonArray resources = request.getAsJsonArray("resources");
		p.add("patterns", resources != null ? resources : new JsonArray());
		if (directory != null) p.addProperty("directory", directory);
		return event("permission.asked", p);
	}

	/** V2 form to the question.asked shape; null for forms the question dialog cannot show. */
	static OpenCodeEvent question(JsonObject form) {
		JsonArray fields = form.getAsJsonArray("fields");
		if (fields == null) return null;
		JsonArray questions = new JsonArray();
		for (JsonElement e : fields) {
			JsonObject field = e.getAsJsonObject();
			String type = str(field, "type");
			if (!"string".equals(type) && !"multiselect".equals(type)) return null;
			JsonObject q = new JsonObject();
			q.addProperty("key", str(field, "key"));
			String title = str(field, "title"), description = str(field, "description");
			q.addProperty("header", title != null ? title : "");
			q.addProperty("question", description != null ? description : (title != null ? title : ""));
			q.addProperty("multiple", "multiselect".equals(type));
			q.addProperty("custom", !field.has("options") || !field.has("custom") || field.get("custom").getAsBoolean());
			JsonArray options = new JsonArray();
			JsonArray v2options = field.getAsJsonArray("options");
			if (v2options != null) for (JsonElement o : v2options) {
				JsonObject option = new JsonObject();
				String label = str(o.getAsJsonObject(), "label");
				option.addProperty("label", label);
				option.addProperty("value", str(o.getAsJsonObject(), "value"));
				String d = str(o.getAsJsonObject(), "description");
				option.addProperty("description", d != null ? d : "");
				options.add(option);
			}
			q.add("options", options);
			questions.add(q);
		}
		JsonObject p = new JsonObject();
		p.addProperty("id", str(form, "id"));
		p.addProperty("sessionID", str(form, "sessionID"));
		p.add("questions", questions);
		return event("question.asked", p);
	}

	/** Question dialog answers (one label list per question) to a V2 form answer. */
	static JsonObject formAnswer(JsonArray questions, JsonArray answers) {
		JsonObject answer = new JsonObject();
		for (int i = 0; i < questions.size() && i < answers.size(); i++) {
			JsonObject q = questions.get(i).getAsJsonObject();
			JsonArray selected = answers.get(i).getAsJsonArray();
			JsonArray values = new JsonArray();
			for (JsonElement s : selected) values.add(optionValue(q, s.getAsString()));
			String key = str(q, "key");
			if (q.has("multiple") && q.get("multiple").getAsBoolean()) answer.add(key, values);
			else if (!values.isEmpty()) answer.add(key, values.get(0));
		}
		return answer;
	}

	private static String optionValue(JsonObject question, String label) {
		JsonArray options = question.getAsJsonArray("options");
		if (options != null) for (JsonElement o : options)
			if (label.equals(str(o.getAsJsonObject(), "label"))) {
				String value = str(o.getAsJsonObject(), "value");
				return value != null ? value : label;
			}
		return label;
	}

	/** file.edited events for a step's changed files, resolved against the session directory. */
	static JsonArray stepFiles(JsonObject data, String directory) {
		JsonArray out = new JsonArray();
		JsonArray files = data == null ? null : data.getAsJsonArray("files");
		if (files == null) return out;
		for (JsonElement f : files) {
			Path path = Path.of(f.getAsString());
			if (!path.isAbsolute() && directory != null) path = Path.of(directory).resolve(path);
			out.add(path.normalize().toString());
		}
		return out;
	}

	// ---- providers / models ---------------------------------------------------

	/** V2 model list to the V1 {@code /config/providers} shape used by the model picker. */
	static JsonObject providers(JsonArray models) {
		JsonObject byProvider = new JsonObject();
		for (JsonElement e : models) {
			JsonObject m = e.getAsJsonObject();
			if (m.has("enabled") && !m.get("enabled").getAsBoolean()) continue;
			String provider = str(m, "providerID");
			String id = str(m, "id");
			if (provider == null || id == null) continue;
			JsonObject p = byProvider.getAsJsonObject(provider);
			if (p == null) {
				p = new JsonObject(); p.addProperty("id", provider); p.add("models", new JsonObject());
				byProvider.add(provider, p);
			}
			JsonObject model = new JsonObject();
			model.addProperty("id", id);
			model.addProperty("name", str(m, "name"));
			if (m.has("limit")) model.add("limit", m.get("limit"));
			JsonObject variants = new JsonObject();
			JsonArray v2variants = m.getAsJsonArray("variants");
			if (v2variants != null) for (JsonElement v : v2variants)
				if (v.isJsonObject() && str(v.getAsJsonObject(), "id") != null)
					variants.add(str(v.getAsJsonObject(), "id"), v.getAsJsonObject());
			model.add("variants", variants);
			p.getAsJsonObject("models").add(id, model);
		}
		JsonArray list = new JsonArray();
		byProvider.entrySet().forEach(entry -> list.add(entry.getValue()));
		JsonObject out = new JsonObject();
		out.add("providers", list);
		return out;
	}

	/** Model.Ref for {@code provider/model}. */
	static JsonObject modelRef(String providerModel, String variant) {
		int slash = providerModel.indexOf('/');
		JsonObject model = new JsonObject();
		model.addProperty("providerID", slash < 0 ? providerModel : providerModel.substring(0, slash));
		model.addProperty("id", slash < 0 ? "" : providerModel.substring(slash + 1));
		if (variant != null && !variant.isBlank()) model.addProperty("variant", variant);
		return model;
	}

	/** {@code provider/model} from a V2 config/model reference (string or object). */
	static String modelString(JsonElement model) {
		if (model == null || model.isJsonNull()) return null;
		if (model.isJsonPrimitive()) return model.getAsString();
		JsonObject o = model.getAsJsonObject();
		String provider = str(o, "providerID");
		String id = str(o, "model");
		if (id == null) id = str(o, "id");
		if (id == null) id = str(o, "modelID");
		return provider != null && id != null ? provider + "/" + id : null;
	}

	// ---- json helpers --------------------------------------------------------

	private static void copy(JsonObject from, JsonObject to, String... keys) {
		for (String key : keys) if (from.has(key)) to.add(key, from.get(key));
	}

	static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()
				? o.get(key).getAsString() : null;
	}

	private static double num(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
	}
}
