package com.opencode.eclipse.core;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

public final class PermissionRelayTest {
	public static void main(String[] args) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		boolean[] replied = { false };
		server.createContext("/api/permission/request", exchange -> {
			byte[] body = "{\"data\":[{\"id\":\"per_1\",\"sessionID\":\"child\",\"action\":\"shell\",\"resources\":[\"ls\"]}]}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var out = exchange.getResponseBody()) { out.write(body); }
		});
		server.createContext("/api/session/child/permission/per_1/reply", exchange -> {
			JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
					StandardCharsets.UTF_8)).getAsJsonObject();
			assert "once".equals(body.get("decision").getAsString());
			replied[0] = true;
			exchange.sendResponseHeaders(200, 0); exchange.close();
		});
		server.start();
		try {
			OpenCodeService service = new OpenCodeService();
			set(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
			service.respondToPermission("per_1", "once", "/tmp");
			assert replied[0];
			JsonObject request = new JsonObject(); request.addProperty("sessionID", "child");
			request.addProperty("id", "per_1"); request.addProperty("action", "shell");
			OpenCodeEvent event = V2.permission(request, "/tmp");
			assert "shell".equals(event.raw().getAsJsonObject("properties").get("permission").getAsString());
			Set<String> children = new HashSet<>(); children.add("child");
			assert OpenCodeService.isForwardableEvent(event, "root", children);
			service.dispose();
		} finally { server.stop(0); }
		System.out.println("PERMISSION RELAY OK");
	}
	private static void set(OpenCodeService service, String name, String value) throws Exception {
		Field f = OpenCodeService.class.getDeclaredField(name); f.setAccessible(true); f.set(service, value);
	}
}
