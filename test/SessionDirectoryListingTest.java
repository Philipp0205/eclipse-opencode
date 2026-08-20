package com.opencode.eclipse.core;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpServer;

public final class SessionDirectoryListingTest {
	public static void main(String[] args) throws Exception {
		Path project = Files.createTempDirectory("opencode-project");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/session", exchange -> {
			String query = exchange.getRequestURI().getRawQuery();
			String expected = "directory=" + java.net.URLEncoder.encode(project.toRealPath().toString(),
					java.nio.charset.StandardCharsets.UTF_8);
			assert expected.equals(query) : query;
			byte[] body = "[{\"id\":\"ses_project\",\"title\":\"Project\"}]".getBytes();
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) { output.write(body); }
		});
		server.start();
		try {
			OpenCodeService service = new OpenCodeService();
			set(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
			set(service, "workspaceDir", "/different/default");
			JsonArray sessions = service.listSessions(project.toString());
			assert sessions.size() == 1;
			assert "ses_project".equals(sessions.get(0).getAsJsonObject().get("id").getAsString());
			service.dispose();
		} finally {
			server.stop(0);
			Files.deleteIfExists(project);
		}
		System.out.println("SESSION DIRECTORY LISTING OK");
	}

	private static void set(OpenCodeService service, String fieldName, String value) throws Exception {
		Field field = OpenCodeService.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(service, value);
	}
}
