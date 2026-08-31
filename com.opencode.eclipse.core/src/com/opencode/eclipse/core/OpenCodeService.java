package com.opencode.eclipse.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Talks to a local {@code opencode serve} process over its REST + SSE API.
 *
 * <p>Java port of the VS Code extension's {@code OpenCodeService.ts}: there is no
 * Java opencode SDK, but the server is a plain HTTP+SSE service, so this spawns the
 * binary and calls the same endpoints with {@link HttpClient}.
 *
 * <p>ponytail: one process per service instance, killed on {@link #dispose()}.
 * Upgrade path: share one server across views if multi-view is wanted.
 */
public final class OpenCodeService {

	/**
	 * Any loopback URL the server may print. Deliberately not tied to the word "listening":
	 * opencode's startup banner has changed wording more than once (it now prints an
	 * {@code OPENCODE_SERVER_PASSWORD} warning first), so the port is matched instead.
	 */
	private static final Pattern LISTEN_URL =
			Pattern.compile("http://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]|0\\.0\\.0\\.0):(\\d+)");

	/** Password line printed by builds that secure the server with a generated credential. */
	private static final Pattern PRINTED_PASSWORD =
			Pattern.compile("(?i)server password\\s*[:=]?\\s*(\\S+)");

	/** Basic-auth user the server expects alongside {@code OPENCODE_SERVER_PASSWORD}. */
	private static final String AUTH_USER = "opencode";

	private static final Duration LISTEN_TIMEOUT = Duration.ofSeconds(25);
	private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(20);
	/**
	 * Bounds only the *headers* of an SSE subscription; the stream itself stays open.
	 * Overridable with {@code -Dopencode.eventTimeoutSeconds} so tests need not wait 30s.
	 */
	private static final Duration EVENT_HEADER_TIMEOUT =
			Duration.ofSeconds(Long.getLong("opencode.eventTimeoutSeconds", 30));
	/**
	 * Silence after which a turn is completed from server status instead of waiting forever.
	 * Overridable with {@code -Dopencode.stallTimeoutSeconds}.
	 */
	private static final Duration STREAM_STALL_TIMEOUT =
			Duration.ofSeconds(Long.getLong("opencode.stallTimeoutSeconds", 60));
	private static final int START_ATTEMPTS = 3;
	private static final int SERVER_LOG_LINES = 40;

	// HTTP/1.1 forced: opencode's server hangs the JDK client's HTTP/2 (h2c) upgrade handshake.
	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(15)).build();

	private volatile Process process;
	private volatile String baseUrl;
	private volatile String workspaceDir;
	private volatile String serverPassword;
	private volatile String serverVersion;
	/** Last lines the server printed, for actionable startup failures. */
	private final Deque<String> serverLog = new ArrayDeque<>();
	private final AtomicReference<String> currentSessionId = new AtomicReference<>();
	private final ConcurrentHashMap<String, PromptRun> prompts = new ConcurrentHashMap<>();
	private final AtomicReference<InputStream> eventStream = new AtomicReference<>();
	private volatile Thread eventThread;
	private volatile String currentSessionTitle = "New Session";
	private volatile String currentSessionDirectory;

	private static final class PromptRun {
		final String sessionId;
		final AtomicReference<InputStream> stream = new AtomicReference<>();
		final AtomicReference<IOException> failure = new AtomicReference<>();
		final AtomicLong lastEvent = new AtomicLong(System.nanoTime());
		volatile CompletableFuture<HttpResponse<String>> post;
		volatile boolean cancelled;
		/** Set when the stall watchdog ended the turn because the server reports it finished. */
		volatile boolean completedByStatus;

		PromptRun(String sessionId) { this.sessionId = sessionId; }
	}

	// ---- lifecycle --------------------------------------------------------

	/**
	 * Spawn {@code opencode serve} in {@code workspaceRoot} and wait until it answers.
	 *
	 * <p>The port is reserved here instead of delegating to {@code --port 0}: opencode stopped
	 * honouring port 0 as "let the OS pick" and now binds its well-known default port (4096),
	 * which makes every Eclipse-owned server fight the user's own TUI/desktop instance for one
	 * port. An explicit loopback port keeps one private server per view, and a lost bind race
	 * is retried with a fresh port because the CLI exits rather than falling back.
	 */
	public synchronized void initialize(String workspaceRoot) throws IOException {
		if (baseUrl != null) {
			return;
		}
		this.workspaceDir = workspaceRoot;
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
			try {
				startServer(workspaceRoot);
				return;
			} catch (IOException e) {
				dispose();
				lastFailure = e;
				if (!isPortConflict(serverOutput())) break;
			}
		}
		throw lastFailure;
	}

	private void startServer(String workspaceRoot) throws IOException {
		int port = reservePort();
		synchronized (serverLog) { serverLog.clear(); }
		serverPassword = envServerPassword();
		var pb = new ProcessBuilder("opencode", "serve", "--port", String.valueOf(port),
				"--hostname", "127.0.0.1");
		pb.environment().put("OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS", "true");
		if (workspaceRoot != null) {
			pb.directory(new java.io.File(workspaceRoot));
		}
		pb.redirectErrorStream(true);
		Process started = process = pb.start();

		CompletableFuture<String> listening = new CompletableFuture<>();
		Thread drain = new Thread(() -> {
			try (var reader = new BufferedReader(new InputStreamReader(
					started.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					record(line);
					String password = parsePrintedPassword(line);
					if (password != null) serverPassword = password;
					String url = parseListenUrl(line, port);
					if (url != null && !listening.isDone()) listening.complete(url);
				}
			} catch (IOException ignored) {
				// Process teardown closes the pipe; onExit below reports the real outcome.
			}
		}, "opencode-stdout");
		drain.setDaemon(true);
		drain.start();
		started.onExit().thenRun(() -> {
			if (!listening.isDone()) listening.completeExceptionally(new IOException(
					"opencode serve exited with code " + started.exitValue() + describeOutput()));
		});
		try {
			baseUrl = listening.get(LISTEN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			awaitHealthy();
		} catch (TimeoutException e) {
			throw new IOException("opencode server did not report a listen URL within "
					+ LISTEN_TIMEOUT.toSeconds() + "s" + describeOutput(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while starting opencode", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof IOException io ? io : new IOException("Failed to start opencode", cause);
		}
	}

	/**
	 * Poll {@code /global/health} until the server answers: it prints its listen URL before it
	 * is necessarily able to serve, so a single request can lose the race and used to surface
	 * as an unexplained startup failure.
	 */
	private void awaitHealthy() throws IOException, InterruptedException {
		long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
		IOException lastFailure = null;
		while (System.nanoTime() < deadline) {
			Process running = process;
			if (running != null && !running.isAlive()) {
				throw new IOException("opencode serve exited with code " + running.exitValue() + describeOutput());
			}
			try {
				probeHealth();
				return;
			} catch (Unrecoverable e) {
				throw e;
			} catch (IOException e) {
				lastFailure = e;
				Thread.sleep(250);
			}
		}
		throw new IOException("opencode server did not become healthy within " + HEALTH_TIMEOUT.toSeconds()
				+ "s" + describeOutput(), lastFailure);
	}

	private void probeHealth() throws IOException, InterruptedException {
		HttpResponse<String> response = http.send(authorized(HttpRequest.newBuilder(uri("/global/health")))
				.timeout(Duration.ofSeconds(5)).GET().build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new Unrecoverable("opencode server requires a password. Set OPENCODE_SERVER_PASSWORD in the "
					+ "environment Eclipse runs in, or unset it so the server starts unsecured.");
		}
		if (response.statusCode() >= 400) {
			throw new IOException("opencode GET /global/health -> " + response.statusCode() + ": " + response.body());
		}
		JsonObject health = JsonParser.parseString(response.body()).getAsJsonObject();
		if (!health.has("healthy") || !health.get("healthy").getAsBoolean()) {
			throw new IOException("opencode server is not healthy");
		}
		serverVersion = optString(health, "version", null);
	}

	/** A startup failure that retrying cannot fix, such as a rejected credential. */
	private static final class Unrecoverable extends IOException {
		private static final long serialVersionUID = 1L;

		Unrecoverable(String message) { super(message); }
	}

	/** Loopback URL printed by the server, preferring the port we asked for. */
	static String parseListenUrl(String line, int expectedPort) {
		if (line == null) return null;
		Matcher matcher = LISTEN_URL.matcher(line);
		String fallback = null;
		while (matcher.find()) {
			if (String.valueOf(expectedPort).equals(matcher.group(1))) return matcher.group();
			if (fallback == null) fallback = matcher.group();
		}
		return fallback;
	}

	static String parsePrintedPassword(String line) {
		if (line == null) return null;
		Matcher matcher = PRINTED_PASSWORD.matcher(line);
		return matcher.find() ? matcher.group(1) : null;
	}

	/** {@code Authorization} value for the server's HTTP basic auth. */
	static String basicAuthHeader(String password) {
		return "Basic " + Base64.getEncoder().encodeToString(
				(AUTH_USER + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	/** True when the captured server output shows the requested port was taken. */
	static boolean isPortConflict(String output) {
		if (output == null) return false;
		String lower = output.toLowerCase(Locale.ROOT);
		return lower.contains("serveerror") || lower.contains("eaddrinuse") || lower.contains("already in use");
	}

	private static String envServerPassword() {
		String password = System.getenv("OPENCODE_SERVER_PASSWORD");
		return password == null || password.isBlank() ? null : password;
	}

	/** Reserve a free loopback port so the plugin never claims opencode's default port. */
	private static int reservePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	private void record(String line) {
		synchronized (serverLog) {
			serverLog.addLast(line);
			while (serverLog.size() > SERVER_LOG_LINES) serverLog.removeFirst();
		}
	}

	/** Last lines the server printed; empty when it printed nothing. */
	public String serverOutput() {
		synchronized (serverLog) {
			return String.join("\n", serverLog);
		}
	}

	private String describeOutput() {
		String output = serverOutput().trim();
		return output.isEmpty() ? "" : ". opencode said: " + output;
	}

	/** Server version reported by {@code /global/health}, or null before startup finished. */
	public String getServerVersion() {
		return serverVersion;
	}

	public boolean isReady() {
		return baseUrl != null;
	}

	/**
	 * Point this service at an already-running server instead of spawning one.
	 *
	 * <p>Only used by the tests under {@code test/}, which exercise the HTTP/SSE behaviour
	 * (auth headers, bounded subscriptions, stalled turns) against a stub server.
	 */
	void attach(String baseUrl, String directory, String password) {
		this.baseUrl = baseUrl;
		this.workspaceDir = directory;
		this.serverPassword = password;
	}

	public String getWorkspaceRoot() {
		return workspaceDir;
	}

	public synchronized void dispose() {
		close(eventStream.getAndSet(null));
		if (eventThread != null) eventThread.interrupt();
		eventThread = null;
		for (PromptRun run : prompts.values()) cancelRun(run);
		prompts.clear();
		Process closing = process;
		process = null;
		if (closing != null) {
			closing.destroy();
			Thread waiter = new Thread(() -> {
				try {
					if (!closing.waitFor(3, TimeUnit.SECONDS)) closing.destroyForcibly();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					closing.destroyForcibly();
				}
			}, "opencode-process-cleanup");
			waiter.setDaemon(true);
			waiter.start();
		}
		baseUrl = null;
		serverVersion = null;
		currentSessionId.set(null);
		currentSessionTitle = "New Session";
		currentSessionDirectory = null;
	}

	// ---- sessions ---------------------------------------------------------

	public String createSession() throws IOException {
		return createSession(workspaceDir);
	}

	public String createSession(String directory) throws IOException {
		String oldWorkspace = workspaceDir;
		if (directory != null && !directory.isBlank()) workspaceDir = java.nio.file.Path.of(directory).toAbsolutePath().normalize().toString();
		JsonObject s = post(sessionPath("/session"), "{}").getAsJsonObject();
		currentSessionId.set(s.get("id").getAsString());
		currentSessionTitle = optString(s, "title", "New Session");
		currentSessionDirectory = optString(s, "directory", workspaceDir);
		if (currentSessionDirectory == null) workspaceDir = oldWorkspace;
		return currentSessionId.get();
	}

	public JsonArray listSessions() throws IOException {
		return get(sessionPath("/session")).getAsJsonArray();
	}

	/**
	 * List sessions belonging to an explicitly selected project directory.
	 *
	 * <p>This does not change the service's active workspace or session. The
	 * directory is canonicalized before being sent because OpenCode scopes the
	 * session endpoint by its {@code directory} query parameter. This overload
	 * is intended for callers that need to inspect several Eclipse project
	 * roots with one service instance.
	 */
	public JsonArray listSessions(String directory) throws IOException {
		return get(sessionPath("/session", canonicalDirectory(directory))).getAsJsonArray();
	}

	public JsonObject getSessionStatus() throws IOException {
		return get(activeSessionPath("/session/status")).getAsJsonObject();
	}

	public boolean isSessionBusy(String sessionId) {
		return prompts.containsKey(sessionId);
	}

	public JsonObject switchSession(String sessionId) throws IOException {
		JsonObject s = get(sessionPath("/session/" + sessionId)).getAsJsonObject();
		return selectSession(s);
	}

	/** Switch to a session while explicitly selecting the directory scope. */
	public JsonObject switchSession(String directory, String sessionId) throws IOException {
		String canonical = canonicalDirectory(directory);
		JsonObject s = get(sessionPath("/session/" + sessionId, canonical)).getAsJsonObject();
		return selectSession(s);
	}

	private JsonObject selectSession(JsonObject s) {
		currentSessionId.set(s.get("id").getAsString());
		currentSessionTitle = optString(s, "title", "New Session");
		currentSessionDirectory = optString(s, "directory", workspaceDir);
		return s;
	}

	public String getCurrentSessionId() {
		return currentSessionId.get();
	}

	public String getCurrentSessionTitle() {
		return currentSessionTitle;
	}

	public String getCurrentSessionDirectory() {
		return currentSessionDirectory != null ? currentSessionDirectory : workspaceDir;
	}

	public JsonArray getMessages(String sessionId) throws IOException {
		return get(activeSessionPath("/session/" + sessionId + "/message")).getAsJsonArray();
	}

	/** Same as {@link #getMessages(String)} but explicitly scoped to {@code directory} instead of
	 * this service's current session directory — for querying a session (e.g. a delegated
	 * subagent) that may live in a different directory than whatever this service is currently
	 * pointed at. */
	public JsonArray getMessages(String sessionId, String directory) throws IOException {
		String path = "/session/" + sessionId + "/message";
		if (directory != null && !directory.isBlank()) path += "?directory=" + enc(directory);
		return get(path).getAsJsonArray();
	}


	public JsonObject renameSession(String sessionId, String title) throws IOException {
		JsonObject body = new JsonObject(); body.addProperty("title", title);
		return patch(activeSessionPath("/session/" + sessionId), body.toString()).getAsJsonObject();
	}

	/** Rename a session explicitly scoped to {@code directory}, without requiring this service's
	 * active session/workspace to match — for callers (e.g. a session history view) acting on a
	 * session that may not currently be open in any ChatView. */
	public JsonObject renameSession(String directory, String sessionId, String title) throws IOException {
		JsonObject body = new JsonObject(); body.addProperty("title", title);
		return patch(sessionPath("/session/" + sessionId, canonicalDirectory(directory)), body.toString()).getAsJsonObject();
	}

	public boolean deleteSession(String sessionId) throws IOException {
		return delete("/session/" + sessionId).getAsBoolean();
	}

	/** Delete a session explicitly scoped to {@code directory}; see {@link #renameSession(String, String, String)}. */
	public boolean deleteSession(String directory, String sessionId) throws IOException {
		return delete(sessionPath("/session/" + sessionId, canonicalDirectory(directory))).getAsBoolean();
	}

	public JsonArray getSessionChildren(String sessionId) throws IOException {
		return get(activeSessionPath("/session/" + sessionId + "/children")).getAsJsonArray();
	}

	/** Return all currently known descendants, including nested task sessions. */
	public JsonArray getSessionDescendants(String sessionId) throws IOException {
		JsonArray descendants = new JsonArray();
		collectSessionDescendants(sessionId, descendants, new HashSet<>());
		return descendants;
	}

	private void collectSessionDescendants(String sessionId, JsonArray descendants, Set<String> seen)
			throws IOException {
		if (!seen.add(sessionId)) return;
		for (JsonElement child : getSessionChildren(sessionId)) {
			JsonObject childObject = child.getAsJsonObject();
			String childId = optString(childObject, "id", null);
			if (childId == null || seen.contains(childId)) continue;
			descendants.add(childObject);
			collectSessionDescendants(childId, descendants, seen);
		}
	}

	public JsonObject forkSession(String sessionId, String messageId) throws IOException {
		JsonObject body = new JsonObject(); if (messageId != null) body.addProperty("messageID", messageId);
		return post(activeSessionPath("/session/" + sessionId + "/fork"), body.toString()).getAsJsonObject();
	}

	public JsonObject unrevertSession(String sessionId) throws IOException {
		return post("/session/" + sessionId + "/unrevert", "{}").getAsJsonObject();
	}

	public JsonObject shareSession(String sessionId) throws IOException {
		return post("/session/" + sessionId + "/share", "{}").getAsJsonObject();
	}

	public JsonObject unshareSession(String sessionId) throws IOException {
		return delete("/session/" + sessionId + "/share").getAsJsonObject();
	}

	public void moveSession(String sessionId, String directory) throws IOException {
		post(activeSessionPath("/experimental/control-plane/move-session"),
				moveSessionBody(sessionId, directory).toString());
		currentSessionDirectory = directory;
	}

	static JsonObject moveSessionBody(String sessionId, String directory) {
		JsonObject body = new JsonObject(); body.addProperty("sessionID", sessionId);
		JsonObject destination = new JsonObject(); destination.addProperty("directory", directory);
		body.add("destination", destination); body.addProperty("moveChanges", false); return body;
	}

	public JsonArray listPendingPermissions() throws IOException { return get(activeSessionPath("/permission")).getAsJsonArray(); }

	public JsonArray listPendingQuestions() throws IOException { return get(activeSessionPath("/question")).getAsJsonArray(); }

	public boolean replyQuestion(String requestId, JsonArray answers) throws IOException {
		JsonObject body = new JsonObject(); body.add("answers", answers);
		return post(sessionPath("/question/" + requestId + "/reply"), body.toString()).getAsBoolean();
	}

	public boolean rejectQuestion(String requestId) throws IOException {
		return post(sessionPath("/question/" + requestId + "/reject"), "{}").getAsBoolean();
	}

	// ---- agents / config --------------------------------------------------

	/** Primary/all-mode agents only, matching the VS Code plugin's filter. */
	public List<JsonObject> getAgents() throws IOException {
		JsonArray all = get("/agent").getAsJsonArray();
		List<JsonObject> out = new ArrayList<>();
		for (JsonElement e : all) {
			JsonObject a = e.getAsJsonObject();
			String mode = optString(a, "mode", "");
			boolean hidden = a.has("hidden") && !a.get("hidden").isJsonNull() && a.get("hidden").getAsBoolean();
			if (!hidden && (mode.equals("primary") || mode.equals("all"))) {
				out.add(a);
			}
		}
		return out;
	}

	public JsonObject getConfig() throws IOException {
		return get("/config").getAsJsonObject();
	}

	/** providers with their model IDs, for the model picker. */
	public JsonObject listProviders() throws IOException {
		return get("/config/providers").getAsJsonObject();
	}

	public JsonObject providerStatus() throws IOException { return get("/provider").getAsJsonObject(); }
	public JsonObject providerAuthMethods() throws IOException { return get("/provider/auth").getAsJsonObject(); }

	public JsonObject authorizeProvider(String provider, int method, JsonObject inputs) throws IOException {
		JsonObject body = new JsonObject(); body.addProperty("method", method); body.add("inputs", inputs);
		return post("/provider/" + enc(provider) + "/oauth/authorize", body.toString()).getAsJsonObject();
	}

	public boolean completeProviderAuth(String provider, int method, String code) throws IOException {
		JsonObject body = new JsonObject(); body.addProperty("method", method);
		if (code != null && !code.isBlank()) body.addProperty("code", code);
		return post("/provider/" + enc(provider) + "/oauth/callback", body.toString(), Duration.ofMinutes(15)).getAsBoolean();
	}

	public boolean setProviderApiKey(String provider, String key) throws IOException {
		return setProviderApiKey(provider, key, null);
	}

	public boolean setProviderApiKey(String provider, String key, JsonObject metadata) throws IOException {
		return put("/auth/" + enc(provider), apiKeyBody(key, metadata).toString()).getAsBoolean();
	}

	static JsonObject apiKeyBody(String key, JsonObject metadata) {
		JsonObject body = new JsonObject(); body.addProperty("type", "api"); body.addProperty("key", key);
		if (metadata != null && !metadata.isEmpty()) body.add("metadata", metadata);
		return body;
	}

	/** MCP server status keyed by configured server name. */
	public JsonObject getMcpStatus() throws IOException {
		return get("/mcp").getAsJsonObject();
	}

	public boolean connectMcp(String name) throws IOException {
		return post("/mcp/" + enc(name) + "/connect", "{}").getAsBoolean();
	}

	public boolean disconnectMcp(String name) throws IOException {
		return post("/mcp/" + enc(name) + "/disconnect", "{}").getAsBoolean();
	}

	public JsonObject getLspStatus() throws IOException { return get("/lsp").getAsJsonObject(); }
	public JsonObject getFormatterStatus() throws IOException { return get("/formatter").getAsJsonObject(); }

	public void compactSession(String sessionId) throws IOException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) throw new IOException("No active session");
		String[] model = resolveModel();
		JsonObject body = new JsonObject();
		body.addProperty("providerID", model[0]); body.addProperty("modelID", model[1]); body.addProperty("auto", false);
		post("/session/" + sid + "/summarize", body.toString());
	}

	public List<CommandInfo> listCommands() throws IOException {
		String dir = workspaceDir != null ? workspaceDir : System.getProperty("user.dir");
		JsonArray data = get(activeSessionPath("/command")).getAsJsonArray();
		List<CommandInfo> commands = new ArrayList<>();
		for (JsonElement element : data) {
			JsonObject command = element.getAsJsonObject();
			List<String> hints = new ArrayList<>();
			JsonArray rawHints = command.getAsJsonArray("hints");
			if (rawHints != null) rawHints.forEach(hint -> hints.add(hint.getAsString()));
			commands.add(new CommandInfo(optString(command, "name", ""), optString(command, "description", ""),
					optString(command, "source", "command"), optString(command, "agent", null),
					optString(command, "model", null), command.has("subtask") && !command.get("subtask").isJsonNull()
							&& command.get("subtask").getAsBoolean(),
					List.copyOf(hints)));
		}
		return List.copyOf(commands);
	}

	/** User-selected model as "providerID/modelID", overriding config. Null = use config. */
	private volatile String overrideModel;

	public void setModel(String providerModel) {
		this.overrideModel = (providerModel == null || providerModel.isBlank()) ? null : providerModel;
	}

	public String getModel() {
		return overrideModel;
	}

	/** SnapshotFileDiff[] for the session (patch, additions, deletions, status per file). */
	public JsonArray getDiff(String sessionId) throws IOException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) {
			throw new IOException("No active session");
		}
		return get(activeSessionPath("/session/" + sid + "/diff")).getAsJsonArray();
	}

	/** provider/model pair: the user override if set, else config, else a default. */
	private String[] resolveModel() throws IOException {
		String model = overrideModel;
		if (model == null) {
			JsonObject cfg = getConfig();
			model = optString(cfg, "model", "anthropic/claude-sonnet-4-5-20250929");
		}
		int slash = model.indexOf('/');
		return slash < 0
				? new String[] { model, "" }
				: new String[] { model.substring(0, slash), model.substring(slash + 1) };
	}

	// ---- prompting --------------------------------------------------------

	private JsonObject promptBody(String text, String agent) throws IOException {
		return promptBody(text, agent, String.join("/", resolveModel()), null);
	}

	static JsonObject promptBody(String text, String agent, String providerModel, String variant) {
		int slash = providerModel.indexOf('/');
		JsonObject model = new JsonObject();
		model.addProperty("providerID", slash < 0 ? providerModel : providerModel.substring(0, slash));
		model.addProperty("modelID", slash < 0 ? "" : providerModel.substring(slash + 1));
		JsonObject part = new JsonObject();
		part.addProperty("type", "text");
		part.addProperty("text", text);
		JsonArray parts = new JsonArray();
		parts.add(part);
		JsonObject body = new JsonObject();
		body.add("model", model);
		body.add("parts", parts);
		if (variant != null && !variant.isBlank()) body.addProperty("variant", variant);
		if (agent != null && !agent.isBlank()) {
			body.addProperty("agent", agent);
		}
		return body;
	}

	/** Blocking prompt; returns the assistant message object. */
	public JsonObject sendPrompt(String text, String sessionId, String agent) throws IOException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) {
			throw new IOException("No active session");
		}
		return post("/session/" + sid + "/message", promptBody(text, agent).toString()).getAsJsonObject();
	}

	/**
	 * Subscribe to SSE, send the prompt, and forward events for {@code sessionId} to
	 * {@code onEvent} until current {@code session.status=idle}. Blocking; run on a worker thread.
	 */
	public void sendPromptStreaming(String text, Consumer<OpenCodeEvent> onEvent,
			String sessionId, String agent) throws IOException, InterruptedException {
		String body = promptBody(text, agent).toString();
		streamRequest(sessionId, onEvent, sid -> HttpRequest.newBuilder(uri(activeSessionPath("/session/" + sid + "/message")))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build());
	}

	public void sendPromptStreaming(String text, Consumer<OpenCodeEvent> onEvent,
			String sessionId, String agent, String model, String variant) throws IOException, InterruptedException {
		String body = promptBody(text, agent, model, variant).toString();
		streamRequest(sessionId, onEvent, sid -> HttpRequest.newBuilder(uri(activeSessionPath("/session/" + sid + "/message")))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
	}

	public void executeCommandStreaming(String command, String arguments, Consumer<OpenCodeEvent> onEvent,
			String sessionId, String agent) throws IOException, InterruptedException {
		executeCommandStreaming(command, arguments, onEvent, sessionId, agent,
				String.join("/", resolveModel()), null, List.of());
	}

	public void executeCommandStreaming(String command, String arguments, Consumer<OpenCodeEvent> onEvent,
			String sessionId, String agent, String model, String variant, List<FilePartInput> files)
			throws IOException, InterruptedException {
		JsonObject body = commandBody(command, arguments, agent, model, variant, files);
		streamRequest(sessionId, onEvent, sid -> HttpRequest.newBuilder(uri(activeSessionPath("/session/" + sid + "/command")))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
	}

	static JsonObject commandBody(String command, String arguments, String agent, String model,
			String variant, List<FilePartInput> files) {
		JsonObject body = new JsonObject();
		body.addProperty("command", command);
		body.addProperty("arguments", arguments != null ? arguments : "");
		if (agent != null && !agent.isBlank()) body.addProperty("agent", agent);
		if (model != null && !model.isBlank()) body.addProperty("model", model);
		if (variant != null && !variant.isBlank()) body.addProperty("variant", variant);
		if (files != null && !files.isEmpty()) {
			JsonArray parts = new JsonArray();
			for (FilePartInput file : files) {
				JsonObject part = new JsonObject(); part.addProperty("type", "file");
				part.addProperty("mime", file.mime()); part.addProperty("filename", file.filename());
				part.addProperty("url", file.url()); parts.add(part);
			}
			body.add("parts", parts);
		}
		return body;
	}

	/** Keep session metadata current even while no prompt stream is active. */
	public synchronized void watchSessionEvents(Consumer<OpenCodeEvent> onEvent) {
		if (eventThread != null && eventThread.isAlive()) return;
		eventThread = new Thread(() -> {
			AtomicReference<InputStream> watched = new AtomicReference<>();
			try {
				String dir = workspaceDir != null ? workspaceDir : System.getProperty("user.dir");
				HttpResponse<InputStream> response = openEventStream("/event?directory=" + enc(dir));
				if (response.statusCode() != 200) return;
				InputStream stream = response.body(); watched.set(stream); eventStream.set(stream);
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) if (line.startsWith("data:")) {
						String json = line.substring(5).trim();
						if (json.isEmpty()) continue;
						JsonObject raw = JsonParser.parseString(json).getAsJsonObject();
						String type = optString(raw, "type", "");
						if (type.equals("session.created") || type.equals("session.updated") || type.equals("session.deleted")
								|| type.equals("session.status") || type.equals("session.idle")
								|| type.equals("permission.asked"))
							onEvent.accept(new OpenCodeEvent(type, raw));
					}
				}
			} catch (Exception ignored) {
				// Server disposal closes the stream; the next view starts a new watcher.
			} finally { eventStream.compareAndSet(watched.get(), null); }
		}, "opencode-session-events");
		eventThread.setDaemon(true); eventThread.start();
	}

	private void streamRequest(String sessionId, Consumer<OpenCodeEvent> onEvent,
			java.util.function.Function<String, HttpRequest> requestFactory) throws IOException, InterruptedException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) {
			throw new IOException("No active session");
		}
		PromptRun run = new PromptRun(sid);
		if (prompts.putIfAbsent(sid, run) != null) throw new IOException("Session is already busy");

		boolean idle = false;
		try {
			Set<String> childSessionIds = new HashSet<>();
			for (JsonElement child : getSessionDescendants(sid)) {
				String childId = optString(child.getAsJsonObject(), "id", null);
				if (childId != null) childSessionIds.add(childId);
			}
			String dir = activeDirectory();
			HttpResponse<InputStream> resp = openEventStream("/event?directory=" + enc(dir));
			if (resp.statusCode() != 200) throw new IOException("Event subscription failed: " + resp.statusCode());
			InputStream eventStream = resp.body();
			run.stream.set(eventStream);
			watchForStall(run);
			HttpRequest prompt = authorized(HttpRequest.newBuilder(requestFactory.apply(sid),
					(name, value) -> !name.equalsIgnoreCase("Authorization"))).build();
			run.post = http.sendAsync(prompt, BodyHandlers.ofString(StandardCharsets.UTF_8));
			run.post.whenComplete((response, error) -> {
				if (error != null || response.statusCode() >= 400) {
					String message = error != null ? error.getMessage()
							: "opencode POST /session/message -> " + response.statusCode() + ": " + response.body();
					run.failure.set(new IOException(message, error));
					close(run.stream.get());
				}
			});

			try (BufferedReader r = new BufferedReader(new InputStreamReader(eventStream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
				if (!line.startsWith("data:")) {
					continue;
				}
				String json = line.substring(5).trim();
				if (json.isEmpty()) {
					continue;
				}
				run.lastEvent.set(System.nanoTime());
				JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
				OpenCodeEvent ev = new OpenCodeEvent(optString(obj, "type", ""), obj);
				String evSid = ev.sessionID();
				if (evSid != null && !evSid.equals(sid) && !childSessionIds.contains(evSid)) {
					if (isChildSessionEvent(ev, sid, childSessionIds)) {
						childSessionIds.add(evSid);
					} else {
						// The event payload didn't carry a resolvable parentID (e.g. the very
						// first event, such as permission.asked, from a subagent spawned mid-turn).
						// Fall back to a live descendant lookup so its events - including
						// permission requests - aren't permanently dropped.
						try {
							for (JsonElement child : getSessionDescendants(sid)) {
								String childId = optString(child.getAsJsonObject(), "id", null);
								if (childId != null) childSessionIds.add(childId);
							}
						} catch (IOException ignored) {
							// Best-effort refresh; the event is simply not forwarded this time.
						}
					}
				}
				if (isForwardableEvent(ev, sid, childSessionIds)) {
					onEvent.accept(ev);
					// A task becoming idle does not complete its parent prompt.
					if (sid.equals(evSid) && ev.isIdle()) { idle = true; break; }
				}
			}
			} catch (IOException streamError) {
				IOException failure = run.failure.get();
				if (failure != null) throw failure;
				if (!run.cancelled && !run.completedByStatus) throw streamError;
			}
			IOException failure = run.failure.get();
			if (failure != null) throw failure;
			if (!idle && !run.cancelled && !run.completedByStatus) {
				abortQuietly(sid);
				throw new IOException("Event stream ended before session became idle");
			}
		} finally {
			cancelRun(run);
			prompts.remove(sid, run);
		}
	}

	/**
	 * Subscribe to SSE with a bounded wait for the response headers only.
	 *
	 * <p>{@code HttpClient.send} has no timeout here on purpose — the body must stay open for the
	 * whole turn — so a blocking send would wait forever when the server accepts the connection
	 * but never answers (opencode's per-directory bootstrap can stall). Waiting on the async
	 * future bounds the handshake without bounding the stream.
	 */
	private HttpResponse<InputStream> openEventStream(String path) throws IOException, InterruptedException {
		HttpRequest request = authorized(HttpRequest.newBuilder(uri(path)))
				.header("Accept", "text/event-stream").GET().build();
		CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(request, BodyHandlers.ofInputStream());
		try {
			return pending.get(EVENT_HEADER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			pending.cancel(true);
			throw new IOException("opencode did not answer the event subscription within "
					+ EVENT_HEADER_TIMEOUT.toSeconds() + "s", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof IOException io ? io
					: new IOException("Event subscription failed: " + cause.getMessage(), cause);
		}
	}

	/**
	 * End a turn whose events stopped arriving while the server already considers the session
	 * finished. Without this the reader blocks on a live-but-silent stream forever, which shows
	 * up as a chat view that stays "Thinking" and refuses further prompts. Both conditions are
	 * required, so a genuinely long, quiet model call (still {@code busy} server-side) is never
	 * cut short.
	 */
	private void watchForStall(PromptRun run) {
		long poll = Math.max(200, STREAM_STALL_TIMEOUT.toMillis() / 4);
		Thread watchdog = new Thread(() -> {
			while (!run.cancelled && run.stream.get() != null) {
				try {
					Thread.sleep(poll);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				if (System.nanoTime() - run.lastEvent.get() < STREAM_STALL_TIMEOUT.toNanos()) continue;
				if (run.post == null || !run.post.isDone()) continue;
				if (isSessionRunningOnServer(run.sessionId)) {
					run.lastEvent.set(System.nanoTime());
					continue;
				}
				run.completedByStatus = true;
				close(run.stream.get());
				return;
			}
		}, "opencode-stream-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	/** True when the server still reports work in flight for {@code sessionId}. */
	private boolean isSessionRunningOnServer(String sessionId) {
		try {
			JsonObject statuses = getSessionStatus();
			JsonElement status = statuses.get(sessionId);
			if (status == null || status.isJsonNull()) return false;
			String type = status.isJsonObject() ? optString(status.getAsJsonObject(), "type", "") : status.getAsString();
			return !"idle".equalsIgnoreCase(type);
		} catch (IOException e) {
			return true; // Unknown: keep waiting rather than ending a live turn.
		}
	}

	private static boolean isChildSessionEvent(OpenCodeEvent event, String rootSessionId,
			Set<String> knownChildIds) {
		String sessionId = event.sessionID();
		if (knownChildIds.contains(sessionId)) return true;
		JsonObject properties = event.raw().getAsJsonObject("properties");
		String parentId = optString(properties, "parentID", null);
		JsonObject info = properties == null ? null : properties.getAsJsonObject("info");
		if (parentId == null) parentId = optString(info, "parentID", null);
		return parentId != null && (rootSessionId.equals(parentId) || knownChildIds.contains(parentId));
	}

	static boolean isForwardableEvent(OpenCodeEvent event, String rootSessionId,
			Set<String> childSessionIds) {
		String eventSessionId = event.sessionID();
		if (eventSessionId == null) return "file.edited".equals(event.type());
		return rootSessionId.equals(eventSessionId) || childSessionIds.contains(eventSessionId);
	}

	// ---- permissions / revert / abort ------------------------------------

	/** reply: "once" | "always" | "reject". */
	public void respondToPermission(String permissionId, String response) throws IOException {
		respondToPermission(permissionId, response, activeDirectory());
	}

	/** Reply to a permission in the directory scope that originated the request. */
	public void respondToPermission(String permissionId, String response, String directory) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("reply", response);
		String scope = directory == null || directory.isBlank() ? null : canonicalDirectory(directory);
		post(sessionPath("/permission/" + permissionId + "/reply", scope), body.toString());
	}

	public JsonObject revertToMessage(String sessionId, String messageId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("messageID", messageId);
		return post("/session/" + sessionId + "/revert", body.toString()).getAsJsonObject();
	}

	public void abortSession(String sessionId) throws IOException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) {
			throw new IOException("No active session to abort");
		}
		try {
			post("/session/" + sid + "/abort", "{}");
		} finally {
			PromptRun run = prompts.get(sid);
			if (run != null) { run.cancelled = true; cancelRun(run); }
		}
	}

	private void abortQuietly(String sessionId) {
		try { post("/session/" + sessionId + "/abort", "{}"); } catch (IOException ignored) { }
	}

	private static void cancelRun(PromptRun run) {
		run.cancelled = true;
		if (run.post != null && !run.post.isDone()) run.post.cancel(true);
		close(run.stream.getAndSet(null));
	}

	private static void close(InputStream stream) {
		if (stream != null) try { stream.close(); } catch (IOException ignored) { }
	}

	// ---- HTTP plumbing ----------------------------------------------------

	private JsonElement get(String path) throws IOException {
		return send(HttpRequest.newBuilder(uri(path)).GET().build());
	}

	private JsonElement post(String path, String body) throws IOException {
		return post(path, body, Duration.ofSeconds(30));
	}

	private JsonElement post(String path, String body, Duration timeout) throws IOException {
		return send(HttpRequest.newBuilder(uri(path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), timeout);
	}

	private JsonElement patch(String path, String body) throws IOException {
		return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
	}

	private JsonElement put(String path, String body) throws IOException {
		return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
	}

	private JsonElement delete(String path) throws IOException {
		return send(HttpRequest.newBuilder(uri(path)).DELETE().build());
	}

	private JsonElement send(HttpRequest req) throws IOException {
		return send(req, Duration.ofSeconds(30));
	}

	private JsonElement send(HttpRequest req, Duration timeout) throws IOException {
		try {
			req = authorized(HttpRequest.newBuilder(req,
					(name, value) -> !name.equalsIgnoreCase("Authorization"))).timeout(timeout).build();
			HttpResponse<String> resp = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() == 401 || resp.statusCode() == 403) {
				throw new IOException("opencode " + req.method() + " " + req.uri().getPath() + " -> "
						+ resp.statusCode() + ". The server requires a password; make sure "
						+ "OPENCODE_SERVER_PASSWORD is set in the environment Eclipse runs in.");
			}
			if (resp.statusCode() >= 400) {
				throw new IOException("opencode " + req.method() + " " + req.uri().getPath()
						+ " -> " + resp.statusCode() + ": " + resp.body());
			}
			String b = resp.body();
			return (b == null || b.isBlank()) ? JsonParser.parseString("{}") : JsonParser.parseString(b);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
	}

	private URI uri(String path) {
		if (baseUrl == null) {
			throw new IllegalStateException("OpenCode not initialized");
		}
		return URI.create(baseUrl + path);
	}

	/** Attach HTTP basic auth when the server was started with a password. */
	private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
		String password = serverPassword;
		return password == null || password.isBlank() ? builder
				: builder.header("Authorization", basicAuthHeader(password));
	}

	private String sessionPath(String path) {
		return sessionPath(path, workspaceDir);
	}

	private String sessionPath(String path, String directory) {
		if (directory == null || directory.isBlank()) return path;
		return path + (path.contains("?") ? "&" : "?") + "directory=" + enc(directory);
	}

	private static String canonicalDirectory(String directory) throws IOException {
		if (directory == null || directory.isBlank()) {
			throw new IOException("Session directory must not be blank");
		}
		try {
			return java.nio.file.Path.of(directory).toRealPath().toString();
		} catch (java.nio.file.InvalidPathException e) {
			throw new IOException("Invalid session directory: " + directory, e);
		}
	}

	private String activeSessionPath(String path) {
		String directory = activeDirectory();
		return directory == null || directory.isBlank() ? path
				: path + (path.contains("?") ? "&" : "?") + "directory=" + enc(directory);
	}

	private String activeDirectory() {
		return currentSessionDirectory != null ? currentSessionDirectory : workspaceDir;
	}

	private static String enc(String s) {
		return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String optString(JsonObject o, String key, String dflt) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : dflt;
	}
}
