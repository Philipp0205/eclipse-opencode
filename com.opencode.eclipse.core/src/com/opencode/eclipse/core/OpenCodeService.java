package com.opencode.eclipse.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

	private static final Pattern LISTEN_URL =
			Pattern.compile("http://[0-9.]+:[0-9]+");

	// HTTP/1.1 forced: opencode's server hangs the JDK client's HTTP/2 (h2c) upgrade handshake.
	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(15)).build();

	private volatile Process process;
	private volatile String baseUrl;
	private volatile String workspaceDir;
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
		volatile CompletableFuture<HttpResponse<String>> post;
		volatile boolean cancelled;

		PromptRun(String sessionId) { this.sessionId = sessionId; }
	}

	// ---- lifecycle --------------------------------------------------------

	/** Spawn {@code opencode serve} in {@code workspaceRoot} and wait for the listen URL. */
	public synchronized void initialize(String workspaceRoot) throws IOException {
		if (baseUrl != null) {
			return;
		}
		this.workspaceDir = workspaceRoot;
		var pb = new ProcessBuilder("opencode", "serve", "--port", "0", "--hostname", "127.0.0.1");
		pb.environment().put("OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS", "true");
		if (workspaceRoot != null) {
			pb.directory(new java.io.File(workspaceRoot));
		}
		pb.redirectErrorStream(true);
		process = pb.start();

		CompletableFuture<String> listening = new CompletableFuture<>();
		Thread drain = new Thread(() -> {
			try (var reader = new BufferedReader(new InputStreamReader(
					process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					Matcher matcher = LISTEN_URL.matcher(line);
					if (!listening.isDone() && line.contains("listening") && matcher.find()) {
						listening.complete(matcher.group());
					}
				}
				if (!listening.isDone()) listening.completeExceptionally(
						new IOException("opencode exited before reporting a listen URL"));
			} catch (IOException e) {
				if (!listening.isDone()) listening.completeExceptionally(e);
			}
		}, "opencode-stdout");
		drain.setDaemon(true);
		drain.start();
		try {
			baseUrl = listening.get(25, TimeUnit.SECONDS);
			JsonObject health = get("/global/health").getAsJsonObject();
			if (!health.has("healthy") || !health.get("healthy").getAsBoolean()) {
				throw new IOException("opencode server is not healthy");
			}
		} catch (TimeoutException e) {
			dispose();
			throw new IOException("opencode server did not report a listen URL within 25s", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			dispose();
			throw new IOException("Interrupted while starting opencode", e);
		} catch (java.util.concurrent.ExecutionException e) {
			dispose();
			throw new IOException("Failed to start opencode", e.getCause());
		}
	}

	public boolean isReady() {
		return baseUrl != null;
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
		return get("/session/status").getAsJsonObject();
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
				HttpRequest request = HttpRequest.newBuilder(uri("/event?directory=" + enc(dir)))
						.header("Accept", "text/event-stream").GET().build();
				HttpResponse<InputStream> response = http.send(request, BodyHandlers.ofInputStream());
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
			HttpRequest sub = HttpRequest.newBuilder(uri("/event?directory=" + enc(dir)))
					.header("Accept", "text/event-stream").GET().build();
			HttpResponse<InputStream> resp = http.send(sub, BodyHandlers.ofInputStream());
			if (resp.statusCode() != 200) throw new IOException("Event subscription failed: " + resp.statusCode());
			InputStream eventStream = resp.body();
			run.stream.set(eventStream);
			HttpRequest prompt = requestFactory.apply(sid);
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
				if (!run.cancelled) throw streamError;
			}
			IOException failure = run.failure.get();
			if (failure != null) throw failure;
			if (!idle && !run.cancelled) {
				abortQuietly(sid);
				throw new IOException("Event stream ended before session became idle");
			}
		} finally {
			cancelRun(run);
			prompts.remove(sid, run);
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
			req = HttpRequest.newBuilder(req, (name, value) -> true).timeout(timeout).build();
			HttpResponse<String> resp = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
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
