package com.opencode.eclipse.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Startup handshake parsing against the shapes current opencode builds actually print.
 *
 * <p>opencode's {@code serve} banner is not a stable contract: it gained an
 * {@code OPENCODE_SERVER_PASSWORD} warning line, some builds print a generated password, and
 * "listening" wording has moved around. These tests pin the tolerant parsing the plugin needs so
 * a banner change cannot turn into a view that loads forever.
 */
public final class ServerStartupTest {
	public static void main(String[] args) {
		listenUrl();
		printedPassword();
		authHeader();
		portConflict();
		System.out.println("SERVER STARTUP OK");
	}

	private static void listenUrl() {
		assert "http://127.0.0.1:4096".equals(
				OpenCodeService.parseListenUrl("opencode server listening on http://127.0.0.1:4096", 4096));
		// Wording is not required: only a loopback URL is.
		assert "http://127.0.0.1:38211".equals(
				OpenCodeService.parseListenUrl("server started at http://127.0.0.1:38211", 38211));
		assert "http://localhost:5199".equals(
				OpenCodeService.parseListenUrl("listening on http://localhost:5199", 5199));
		// The requested port wins over any other URL on the same line.
		assert "http://127.0.0.1:5199".equals(OpenCodeService.parseListenUrl(
				"proxy http://127.0.0.1:4096 -> server http://127.0.0.1:5199", 5199));
		// A port we did not ask for is still accepted: the CLI may fall back on its own.
		assert "http://127.0.0.1:4096".equals(
				OpenCodeService.parseListenUrl("opencode server listening on http://127.0.0.1:4096", 5199));
		assert OpenCodeService.parseListenUrl("Warning: OPENCODE_SERVER_PASSWORD is not set", 4096) == null;
		assert OpenCodeService.parseListenUrl("see https://opencode.ai/docs for details", 4096) == null;
		assert OpenCodeService.parseListenUrl(null, 4096) == null;
	}

	private static void printedPassword() {
		assert "abc123".equals(OpenCodeService.parsePrintedPassword("server password abc123"));
		assert "abc123".equals(OpenCodeService.parsePrintedPassword("opencode server password: abc123"));
		assert OpenCodeService.parsePrintedPassword(
				"Warning: OPENCODE_SERVER_PASSWORD is not set; server is unsecured.") == null;
		assert OpenCodeService.parsePrintedPassword("opencode server listening on http://127.0.0.1:4096") == null;
	}

	private static void authHeader() {
		// The server authenticates HTTP basic auth as user "opencode" with the configured password.
		String expected = "Basic " + Base64.getEncoder()
				.encodeToString("opencode:secret123".getBytes(StandardCharsets.UTF_8));
		assert expected.equals(OpenCodeService.basicAuthHeader("secret123"))
				: OpenCodeService.basicAuthHeader("secret123");
	}

	private static void portConflict() {
		assert OpenCodeService.isPortConflict("Error: Unexpected error\n\nServeError");
		assert OpenCodeService.isPortConflict("Error: Port 4096 is already in use");
		assert OpenCodeService.isPortConflict("listen EADDRINUSE: address already in use");
		assert !OpenCodeService.isPortConflict("opencode server listening on http://127.0.0.1:4096");
		assert !OpenCodeService.isPortConflict(null);
	}
}
