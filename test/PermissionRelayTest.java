package com.opencode.eclipse.core;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

public final class PermissionRelayTest {
	public static void main(String[] args) throws Exception {
		Path directory = Files.createTempDirectory("permission-scope");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/permission/p1/reply", exchange -> {
			String expected = "directory=" + java.net.URLEncoder.encode(directory.toRealPath().toString(), StandardCharsets.UTF_8);
			assert expected.equals(exchange.getRequestURI().getRawQuery()) : exchange.getRequestURI();
			exchange.sendResponseHeaders(200, 0); exchange.close();
		});
		server.start();
		try {
			OpenCodeService service = new OpenCodeService();
			set(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
			service.respondToPermission("p1", "once", directory.toString());
			JsonObject childPermission = new JsonObject(); childPermission.addProperty("sessionID", "child");
			childPermission.addProperty("id", "p1");
			OpenCodeEvent event = new OpenCodeEvent("permission.asked", new JsonObject());
			Set<String> children = new HashSet<>(); children.add("child");
			assert OpenCodeService.isForwardableEvent(eventWithSession(event, childPermission), "root", children);
			service.dispose();
		} finally { server.stop(0); Files.deleteIfExists(directory); }
		System.out.println("PERMISSION RELAY OK");
	}
	private static OpenCodeEvent eventWithSession(OpenCodeEvent ignored, JsonObject permission) {
		JsonObject raw = new JsonObject(); raw.addProperty("type", "permission.asked"); raw.add("properties", permission);
		return new OpenCodeEvent("permission.asked", raw);
	}
	private static void set(OpenCodeService service, String name, String value) throws Exception {
		Field f = OpenCodeService.class.getDeclaredField(name); f.setAccessible(true); f.set(service, value);
	}
}
