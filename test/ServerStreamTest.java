package com.opencode.eclipse.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Streaming and auth behaviour against a stub server, covering the ways a real opencode server
 * can leave a prompt turn waiting forever:
 *
 * <ul>
 * <li>an event subscription whose headers never arrive (the per-directory bootstrap stalls),
 * <li>a subscription that stays open but silent after the turn has actually finished,
 * <li>a server started with {@code OPENCODE_SERVER_PASSWORD}, which rejects unauthenticated calls.
 * </ul>
 *
 * <p>Run with short {@code opencode.eventTimeoutSeconds}/{@code opencode.stallTimeoutSeconds} so
 * the bounds are observable in a few seconds instead of the production minute.
 */
public final class ServerStreamTest {
	private static final String SESSION = "ses_test";

	public static void main(String[] args) throws Exception {
		unansweredSubscriptionFailsFast();
		silentStreamCompletesFromStatus();
		passwordProtectedServerIsExplained();
		configuredPasswordAuthenticates();
		System.out.println("SERVER STREAM OK");
	}

	/** No response headers at all: the turn must fail, not block until Eclipse is restarted. */
	private static void unansweredSubscriptionFailsFast() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = server();
		server.createContext("/session/" + SESSION + "/children", json("[]"));
		server.createContext("/event", exchange -> {
			await(release);
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});
		server.start();
		try {
			OpenCodeService service = attached(server, null);
			long start = System.nanoTime();
			try {
				service.sendPromptStreaming("hi", event -> { }, SESSION, null, "provider/model", null);
				assert false : "expected the unanswered subscription to fail";
			} catch (IOException expected) {
				long seconds = (System.nanoTime() - start) / 1_000_000_000L;
				assert expected.getMessage().contains("event subscription") : expected.getMessage();
				assert seconds < 20 : "took " + seconds + "s";
			}
			// The failed turn must not leave the session marked busy forever.
			assert !service.isSessionBusy(SESSION);
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	/** Headers arrive, then nothing: completion falls back to the server's own session status. */
	private static void silentStreamCompletesFromStatus() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = server();
		server.createContext("/session/" + SESSION + "/children", json("[]"));
		server.createContext("/session/" + SESSION + "/message", json("{}"));
		server.createContext("/session/status", json("{}")); // no entry: not running
		server.createContext("/event", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(": open\n\n".getBytes(StandardCharsets.UTF_8));
				body.flush();
				await(release);
			} catch (IOException ignored) {
				// The client closes the stream once it stops waiting; that is the point.
			}
		});
		server.start();
		try {
			OpenCodeService service = attached(server, null);
			long start = System.nanoTime();
			service.sendPromptStreaming("hi", event -> { }, SESSION, null, "provider/model", null);
			long seconds = (System.nanoTime() - start) / 1_000_000_000L;
			assert seconds < 20 : "took " + seconds + "s";
			assert !service.isSessionBusy(SESSION);
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	/** A 401 must name the cause instead of surfacing as a bare status code. */
	private static void passwordProtectedServerIsExplained() throws Exception {
		HttpServer server = server();
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
		});
		server.start();
		try {
			OpenCodeService service = attached(server, null);
			try {
				service.listSessions();
				assert false : "expected the 401 to be reported";
			} catch (IOException expected) {
				assert expected.getMessage().contains("OPENCODE_SERVER_PASSWORD") : expected.getMessage();
			}
		} finally {
			server.stop(0);
		}
	}

	/** With a password configured, every request carries the basic-auth header the server wants. */
	private static void configuredPasswordAuthenticates() throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		HttpServer server = server();
		server.createContext("/session", exchange -> {
			String authorization = exchange.getRequestHeaders().getFirst("Authorization");
			seen.set(authorization);
			if (!OpenCodeService.basicAuthHeader("secret123").equals(authorization)) {
				exchange.sendResponseHeaders(401, -1);
				exchange.close();
				return;
			}
			respond(exchange, "[]");
		});
		server.start();
		try {
			OpenCodeService service = attached(server, "secret123");
			assert service.listSessions().isEmpty();
			assert OpenCodeService.basicAuthHeader("secret123").equals(seen.get()) : seen.get();
		} finally {
			server.stop(0);
		}
	}

	private static HttpServer server() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "stub-opencode");
			thread.setDaemon(true);
			return thread;
		}));
		return server;
	}

	private static OpenCodeService attached(HttpServer server, String password) {
		OpenCodeService service = new OpenCodeService();
		service.attach("http://127.0.0.1:" + server.getAddress().getPort(),
				System.getProperty("java.io.tmpdir"), password);
		return service;
	}

	private static com.sun.net.httpserver.HttpHandler json(String body) {
		return exchange -> respond(exchange, body);
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(60, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
