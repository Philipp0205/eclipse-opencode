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
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Talks to a local OpenCode V2 {@code opencode serve} process over its REST + SSE API.
 *
 * <p>Spawns the binary, authenticates with the per-process server password it prints on startup,
 * and converts V2 payloads (see {@link V2}) into the message/event shapes the UI renders.
 *
 * <p>ponytail: one process per service instance, killed on {@link #dispose()}.
 */
public final class OpenCodeService {

	private static final Pattern LISTEN_URL = Pattern.compile("http://[0-9.]+:[0-9]+");
	private static final Pattern PASSWORD = Pattern.compile("server password (\\S+)");
	private static final long REFRESH_INTERVAL_MS = 150;

	// HTTP/1.1 forced: opencode's server hangs the JDK client's HTTP/2 (h2c) upgrade handshake.
	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(15)).build();

	private volatile Process process;
	private volatile String baseUrl;
	private volatile String authorization;
	private volatile String workspaceDir;
	private final AtomicReference<String> currentSessionId = new AtomicReference<>();
	private final ConcurrentHashMap<String, PromptRun> prompts = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, JsonObject> pendingForms = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> formSessions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> sessionParents = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, JsonArray> integrationMethods = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> oauthAttempts = new ConcurrentHashMap<>();
	private final AtomicReference<InputStream> eventStream = new AtomicReference<>();
	private volatile Thread eventThread;
	private volatile String currentSessionTitle = "New Session";
	private volatile String currentSessionDirectory;
	private volatile String appliedModel;
	private volatile String appliedAgent;

	private static final class PromptRun {
		final AtomicReference<InputStream> stream = new AtomicReference<>();
		final AtomicReference<IOException> failure = new AtomicReference<>();
		volatile CompletableFuture<HttpResponse<String>> post;
		volatile boolean cancelled;
	}

	// ---- lifecycle --------------------------------------------------------

	/** Spawn {@code opencode serve} in {@code workspaceRoot} and wait for the listen URL. */
	public synchronized void initialize(String workspaceRoot) throws IOException {
		if (baseUrl != null) return;
		this.workspaceDir = workspaceRoot;
		var pb = new ProcessBuilder("opencode", "serve", "--port", "0", "--hostname", "127.0.0.1");
		if (workspaceRoot != null) pb.directory(new java.io.File(workspaceRoot));
		pb.redirectErrorStream(true);
		process = pb.start();

		CompletableFuture<String> listening = new CompletableFuture<>();
		CompletableFuture<String> password = new CompletableFuture<>();
		Thread drain = new Thread(() -> {
			try (var reader = new BufferedReader(new InputStreamReader(
					process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					Matcher url = LISTEN_URL.matcher(line);
					if (!listening.isDone() && line.contains("listening") && url.find()) listening.complete(url.group());
					Matcher pw = PASSWORD.matcher(line);
					if (!password.isDone() && pw.find()) password.complete(pw.group(1));
				}
				IOException exited = new IOException("opencode exited before reporting a listen URL");
				listening.completeExceptionally(exited);
				password.completeExceptionally(exited);
			} catch (IOException e) {
				listening.completeExceptionally(e);
				password.completeExceptionally(e);
			}
		}, "opencode-stdout");
		drain.setDaemon(true);
		drain.start();
		try {
			String url = listening.get(25, TimeUnit.SECONDS);
			String secret = password.get(5, TimeUnit.SECONDS);
			authorization = "Basic " + Base64.getEncoder().encodeToString(
					("opencode:" + secret).getBytes(StandardCharsets.UTF_8));
			baseUrl = url;
			JsonObject info = get("/api/info").getAsJsonObject();
			if (!info.has("version")) throw new IOException("opencode server did not report its version");
			awaitLocationLoaded(workspaceRoot);
		} catch (TimeoutException e) {
			dispose();
			throw new IOException("opencode server did not report a listen URL and password within 25s", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			dispose();
			throw new IOException("Interrupted while starting opencode", e);
		} catch (java.util.concurrent.ExecutionException e) {
			dispose();
			throw new IOException("Failed to start opencode", e.getCause());
		} catch (IOException e) {
			dispose();
			throw e;
		}
	}

	/** A freshly started server answers location-scoped lists empty until the location has loaded. */
	private void awaitLocationLoaded(String directory) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			JsonElement agents = data(get(withLocation("/api/agent", directory)));
			if (agents != null && agents.isJsonArray() && !agents.getAsJsonArray().isEmpty()) break;
			Thread.sleep(250);
		}
		// Commands and skills finish loading after agents; wait until their count settles.
		int previous = -1;
		while (System.currentTimeMillis() < deadline) {
			JsonElement commands = data(get(withLocation("/api/command", directory)));
			int count = commands != null && commands.isJsonArray() ? commands.getAsJsonArray().size() : 0;
			if (count > 0 && count == previous) return;
			previous = count;
			Thread.sleep(500);
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
		authorization = null;
		currentSessionId.set(null);
		currentSessionTitle = "New Session";
		currentSessionDirectory = null;
		appliedModel = null;
		appliedAgent = null;
	}

	// ---- sessions ---------------------------------------------------------

	public String createSession() throws IOException {
		return createSession(workspaceDir);
	}

	public String createSession(String directory) throws IOException {
		String target = directory != null && !directory.isBlank()
				? java.nio.file.Path.of(directory).toAbsolutePath().normalize().toString() : workspaceDir;
		JsonObject body = new JsonObject();
		if (target != null) body.add("location", location(target));
		JsonObject s = V2.session(data(post("/api/session", body.toString())).getAsJsonObject());
		if (target != null) workspaceDir = target;
		selectSession(s);
		return currentSessionId.get();
	}

	public JsonArray listSessions() throws IOException {
		return listSessionsIn(workspaceDir);
	}

	/**
	 * List sessions belonging to an explicitly selected project directory.
	 *
	 * <p>This does not change the service's active workspace or session. The directory is
	 * canonicalized because OpenCode scopes the session list by its {@code directory} filter.
	 */
	public JsonArray listSessions(String directory) throws IOException {
		return listSessionsIn(canonicalDirectory(directory));
	}

	private JsonArray listSessionsIn(String directory) throws IOException {
		String path = "/api/session?limit=200&order=desc";
		if (directory != null && !directory.isBlank()) path += "&directory=" + enc(directory);
		JsonArray sessions = V2.sessions(get(path));
		for (JsonElement s : sessions) rememberParent(s.getAsJsonObject());
		return sessions;
	}

	public boolean isSessionBusy(String sessionId) {
		return prompts.containsKey(sessionId);
	}

	public JsonObject switchSession(String sessionId) throws IOException {
		return selectSession(getSession(sessionId));
	}

	/** Switch to a session; the directory is recorded on the session itself in V2. */
	public JsonObject switchSession(String directory, String sessionId) throws IOException {
		return switchSession(sessionId);
	}

	private JsonObject getSession(String sessionId) throws IOException {
		JsonObject s = V2.session(data(get("/api/session/" + enc(sessionId))).getAsJsonObject());
		rememberParent(s);
		return s;
	}

	private JsonObject selectSession(JsonObject s) {
		currentSessionId.set(s.get("id").getAsString());
		currentSessionTitle = optString(s, "title", "New Session");
		currentSessionDirectory = optString(s, "directory", workspaceDir);
		appliedModel = V2.modelString(s.get("model"));
		appliedAgent = optString(s, "agent", null);
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

	/** Conversation messages as {@code {info, parts}}, oldest first. */
	public JsonArray getMessages(String sessionId) throws IOException {
		JsonArray all = new JsonArray();
		String cursor = null;
		for (int page = 0; page < 100; page++) {
			String path = "/api/session/" + enc(sessionId) + "/message?limit=200"
					+ (cursor != null ? "&cursor=" + enc(cursor) : "&order=asc");
			JsonObject response = get(path).getAsJsonObject();
			JsonArray items = response.getAsJsonArray("data");
			if (items != null) all.addAll(items);
			JsonObject next = response.getAsJsonObject("cursor");
			cursor = optString(next, "next", null);
			if (cursor == null || items == null || items.isEmpty()) break;
		}
		return V2.messages(all, sessionId);
	}

	/** Messages of a session that may live in another directory; V2 sessions carry their own location. */
	public JsonArray getMessages(String sessionId, String directory) throws IOException {
		return getMessages(sessionId);
	}

	public JsonObject renameSession(String sessionId, String title) throws IOException {
		JsonObject body = new JsonObject(); body.addProperty("title", title);
		return V2.session(data(patch("/api/session/" + enc(sessionId), body.toString())).getAsJsonObject());
	}

	/** Rename a session that may not currently be open in any ChatView. */
	public JsonObject renameSession(String directory, String sessionId, String title) throws IOException {
		return renameSession(sessionId, title);
	}

	public boolean deleteSession(String sessionId) throws IOException {
		delete("/api/session/" + enc(sessionId));
		return true;
	}

	/** Delete a session that may not currently be open in any ChatView. */
	public boolean deleteSession(String directory, String sessionId) throws IOException {
		return deleteSession(sessionId);
	}

	public JsonArray getSessionChildren(String sessionId) throws IOException {
		JsonArray children = V2.sessions(get("/api/session?limit=200&parentID=" + enc(sessionId)));
		for (JsonElement child : children) sessionParents.put(child.getAsJsonObject().get("id").getAsString(), sessionId);
		return children;
	}

	/** Return all currently known descendants, including nested subagent sessions. */
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
		JsonObject body = new JsonObject(); if (messageId != null) body.addProperty("before", messageId);
		return V2.session(data(post("/api/session/" + enc(sessionId) + "/fork", body.toString())).getAsJsonObject());
	}

	/** Discard a staged revert, restoring the reverted messages. */
	public JsonObject unrevertSession(String sessionId) throws IOException {
		delete("/api/session/" + enc(sessionId) + "/revert");
		return getSession(sessionId);
	}

	public void moveSession(String sessionId, String directory) throws IOException {
		post("/api/session/" + enc(sessionId) + "/move", moveSessionBody(directory).toString());
		currentSessionDirectory = directory;
	}

	static JsonObject moveSessionBody(String directory) {
		JsonObject body = new JsonObject(); body.addProperty("directory", directory); return body;
	}

	/** Pending permission requests in the permission.asked properties shape. */
	public JsonArray listPendingPermissions() throws IOException {
		JsonArray out = new JsonArray();
		JsonElement list = data(get(withLocation("/api/permission/request", activeDirectory())));
		if (list != null && list.isJsonArray()) for (JsonElement e : list.getAsJsonArray())
			out.add(V2.permission(e.getAsJsonObject(), activeDirectory()).raw().getAsJsonObject("properties"));
		return out;
	}

	/** Pending question forms in the question.asked properties shape. */
	public JsonArray listPendingQuestions() throws IOException {
		JsonArray out = new JsonArray();
		JsonElement list = data(get(withLocation("/api/form", activeDirectory())));
		if (list != null && list.isJsonArray()) for (JsonElement e : list.getAsJsonArray()) {
			OpenCodeEvent question = questionEvent(e.getAsJsonObject());
			if (question != null) out.add(question.raw().getAsJsonObject("properties"));
		}
		return out;
	}

	private OpenCodeEvent questionEvent(JsonObject form) {
		OpenCodeEvent question = V2.question(form);
		if (question != null) {
			JsonObject properties = question.raw().getAsJsonObject("properties");
			pendingForms.put(properties.get("id").getAsString(), properties);
			formSessions.put(properties.get("id").getAsString(), optString(form, "sessionID", ""));
		}
		return question;
	}

	public boolean replyQuestion(String requestId, JsonArray answers) throws IOException {
		JsonObject form = pendingForms.remove(requestId);
		String session = formSessions.remove(requestId);
		if (form == null || session == null) throw new IOException("Unknown question " + requestId);
		JsonObject body = new JsonObject(); body.add("answer", V2.formAnswer(form.getAsJsonArray("questions"), answers));
		post("/api/session/" + enc(session) + "/form/" + enc(requestId) + "/reply", body.toString());
		return true;
	}

	public boolean rejectQuestion(String requestId) throws IOException {
		pendingForms.remove(requestId);
		String session = formSessions.remove(requestId);
		if (session == null) throw new IOException("Unknown question " + requestId);
		delete("/api/session/" + enc(session) + "/form/" + enc(requestId));
		return true;
	}

	// ---- agents / config --------------------------------------------------

	/** Visible primary/all-mode agents. {@code name} is the agent ID sent back to OpenCode. */
	public List<JsonObject> getAgents() throws IOException {
		JsonElement all = data(get(withLocation("/api/agent", activeDirectory())));
		List<JsonObject> out = new ArrayList<>();
		if (all == null || !all.isJsonArray()) return out;
		for (JsonElement e : all.getAsJsonArray()) {
			JsonObject a = e.getAsJsonObject().deepCopy();
			String mode = optString(a, "mode", "");
			boolean hidden = a.has("hidden") && !a.get("hidden").isJsonNull() && a.get("hidden").getAsBoolean();
			if (!hidden && (mode.equals("primary") || mode.equals("all"))) {
				String id = optString(a, "id", null);
				if (id != null) a.addProperty("name", id);
				out.add(a);
			}
		}
		return out;
	}

	/** Effective settings the UI reads: currently only {@code model} as {@code provider/model}. */
	public JsonObject getConfig() throws IOException {
		JsonObject config = new JsonObject();
		try {
			JsonElement model = data(get(withLocation("/api/model/default", activeDirectory())));
			if (model != null && model.isJsonObject()) {
				String provider = optString(model.getAsJsonObject(), "providerID", null);
				String id = optString(model.getAsJsonObject(), "id", null);
				if (provider != null && id != null) config.addProperty("model", provider + "/" + id);
			}
		} catch (IOException ignored) {
			// No default model configured.
		}
		return config;
	}

	/** Providers with their model IDs, for the model picker. */
	public JsonObject listProviders() throws IOException {
		JsonElement models = data(get(withLocation("/api/model", activeDirectory())));
		return V2.providers(models != null && models.isJsonArray() ? models.getAsJsonArray() : new JsonArray());
	}

	/** {@code connected}: IDs of providers that can serve models. */
	public JsonObject providerStatus() throws IOException {
		JsonArray connected = new JsonArray();
		JsonElement providers = data(get(withLocation("/api/provider", activeDirectory())));
		if (providers != null && providers.isJsonArray()) for (JsonElement p : providers.getAsJsonArray()) {
			String id = optString(p.getAsJsonObject(), "id", null);
			if (id != null) connected.add(id);
		}
		JsonObject status = new JsonObject(); status.add("connected", connected);
		return status;
	}

	/** Connection methods per integration: {@code {provider: [{type: api|oauth, label, prompts}]}}. */
	public JsonObject providerAuthMethods() throws IOException {
		JsonObject result = new JsonObject();
		JsonElement list = data(get("/api/integration"));
		if (list == null || !list.isJsonArray()) return result;
		for (JsonElement e : list.getAsJsonArray()) {
			JsonObject integration = e.getAsJsonObject();
			String id = optString(integration, "id", null);
			JsonArray methods = new JsonArray();
			JsonArray v2methods = integration.getAsJsonArray("methods");
			if (id == null || v2methods == null) continue;
			JsonArray accepted = new JsonArray();
			for (JsonElement m : v2methods) {
				JsonObject method = m.getAsJsonObject();
				String type = optString(method, "type", "");
				if (!type.equals("key") && !type.equals("oauth")) continue;
				JsonObject out = new JsonObject();
				out.addProperty("type", type.equals("key") ? "api" : "oauth");
				out.addProperty("label", optString(method, "label", type.equals("key") ? "API key" : "OAuth"));
				out.add("prompts", authPrompts(method.getAsJsonArray("form")));
				methods.add(out);
				accepted.add(method);
			}
			if (methods.isEmpty()) continue;
			integrationMethods.put(id, accepted);
			result.add(id, methods);
		}
		return result;
	}

	static JsonArray authPrompts(JsonArray form) {
		JsonArray prompts = new JsonArray();
		if (form == null) return prompts;
		for (JsonElement f : form) {
			JsonObject field = f.getAsJsonObject();
			JsonObject prompt = new JsonObject();
			prompt.addProperty("key", optString(field, "key", ""));
			JsonArray options = field.getAsJsonArray("options");
			prompt.addProperty("type", options != null ? "select" : "text");
			String title = optString(field, "title", optString(field, "key", ""));
			prompt.addProperty("message", title);
			if (options != null) prompt.add("options", options);
			JsonArray when = field.getAsJsonArray("when");
			if (when != null && !when.isEmpty()) prompt.add("when", when.get(0));
			prompts.add(prompt);
		}
		return prompts;
	}

	public JsonObject authorizeProvider(String provider, int method, JsonObject inputs) throws IOException {
		JsonObject v2method = integrationMethod(provider, method);
		JsonObject body = new JsonObject();
		body.addProperty("methodID", optString(v2method, "id", ""));
		if (inputs != null && !inputs.isEmpty()) body.add("answer", inputs);
		JsonObject attempt = data(post("/api/integration/" + enc(provider) + "/connect/oauth", body.toString()))
				.getAsJsonObject();
		oauthAttempts.put(provider, optString(attempt, "attemptID", ""));
		JsonObject auth = new JsonObject();
		auth.addProperty("url", optString(attempt, "url", null));
		auth.addProperty("instructions", optString(attempt, "instructions", ""));
		auth.addProperty("method", "code".equals(optString(attempt, "mode", "")) ? "code" : "auto");
		return auth;
	}

	public boolean completeProviderAuth(String provider, int method, String code) throws IOException {
		String attempt = oauthAttempts.get(provider);
		if (attempt == null) throw new IOException("No authorization in progress for " + provider);
		String base = "/api/integration/" + enc(provider) + "/connect/oauth/" + enc(attempt);
		if (code != null && !code.isBlank()) {
			JsonObject body = new JsonObject(); body.addProperty("code", code);
			post(base + "/complete", body.toString(), Duration.ofMinutes(2));
			oauthAttempts.remove(provider);
			return true;
		}
		long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15);
		while (System.currentTimeMillis() < deadline) {
			JsonObject status = data(get(base)).getAsJsonObject();
			String state = optString(status, "status", "pending");
			if (!"pending".equals(state)) {
				oauthAttempts.remove(provider);
				if (state.equals("failed") || state.equals("cancelled") || state.equals("expired"))
					throw new IOException("Authorization " + state + ": " + optString(status, "message", ""));
				return true;
			}
			try { Thread.sleep(2000); } catch (InterruptedException e) {
				Thread.currentThread().interrupt(); throw new IOException("interrupted", e);
			}
		}
		throw new IOException("Authorization timed out");
	}

	private JsonObject integrationMethod(String provider, int index) throws IOException {
		JsonArray methods = integrationMethods.get(provider);
		if (methods == null) { providerAuthMethods(); methods = integrationMethods.get(provider); }
		if (methods == null || index < 0 || index >= methods.size()) throw new IOException("Unknown method for " + provider);
		return methods.get(index).getAsJsonObject();
	}

	public boolean setProviderApiKey(String provider, String key) throws IOException {
		return setProviderApiKey(provider, key, null);
	}

	public boolean setProviderApiKey(String provider, String key, JsonObject metadata) throws IOException {
		post("/api/integration/" + enc(provider) + "/connect/key", apiKeyBody(key, metadata).toString());
		return true;
	}

	static JsonObject apiKeyBody(String key, JsonObject metadata) {
		JsonObject body = new JsonObject(); body.addProperty("key", key);
		if (metadata != null && !metadata.isEmpty()) body.add("answer", metadata);
		return body;
	}

	/** MCP server status keyed by configured server name: {@code {name: {status, error?}}}. */
	public JsonObject getMcpStatus() throws IOException {
		JsonObject result = new JsonObject();
		JsonElement list = data(get(withLocation("/api/mcp", activeDirectory())));
		if (list != null && list.isJsonArray()) for (JsonElement e : list.getAsJsonArray()) {
			JsonObject server = e.getAsJsonObject();
			JsonObject status = server.getAsJsonObject("status");
			result.add(optString(server, "name", "?"), status != null ? status : new JsonObject());
		}
		return result;
	}

	public boolean connectMcp(String name) throws IOException {
		post("/api/experimental/mcp/" + enc(name) + "/connect", "{}");
		return true;
	}

	public boolean disconnectMcp(String name) throws IOException {
		post("/api/experimental/mcp/" + enc(name) + "/disconnect", "{}");
		return true;
	}

	public void compactSession(String sessionId) throws IOException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) throw new IOException("No active session");
		post("/api/session/" + enc(sid) + "/compact", "{}");
	}

	public List<CommandInfo> listCommands() throws IOException {
		List<CommandInfo> commands = new ArrayList<>();
		JsonElement data = data(get(withLocation("/api/command", activeDirectory())));
		if (data != null && data.isJsonArray()) for (JsonElement element : data.getAsJsonArray()) {
			JsonObject command = element.getAsJsonObject();
			commands.add(new CommandInfo(optString(command, "name", ""), optString(command, "description", ""),
					"command", optString(command, "agent", null), V2.modelString(command.get("model")),
					command.has("subagent") && command.get("subagent").isJsonPrimitive()
							&& command.get("subagent").getAsBoolean(),
					List.of()));
		}
		JsonElement skills = data(get(withLocation("/api/skill", activeDirectory())));
		if (skills != null && skills.isJsonArray()) for (JsonElement element : skills.getAsJsonArray()) {
			JsonObject skill = element.getAsJsonObject();
			String id = optString(skill, "id", null);
			if (id == null || commands.stream().anyMatch(c -> c.name().equals(id))) continue;
			commands.add(new CommandInfo(id, optString(skill, "description", ""), "skill", null, null, false, List.of()));
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

	/** File diffs for the session (file, patch, additions, deletions, status). */
	public JsonArray getDiff(String sessionId) throws IOException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) throw new IOException("No active session");
		JsonElement diff = data(get("/api/session/" + enc(sid) + "/diff"));
		return diff != null && diff.isJsonArray() ? diff.getAsJsonArray() : new JsonArray();
	}

	// ---- prompting --------------------------------------------------------

	static JsonObject promptBody(String text, List<FilePartInput> files, List<String> skills) {
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		addFiles(body, files);
		if (skills != null && !skills.isEmpty()) {
			JsonArray list = new JsonArray();
			for (String skill : skills) { JsonObject s = new JsonObject(); s.addProperty("id", skill); list.add(s); }
			body.add("skills", list);
		}
		return body;
	}

	static JsonObject commandBody(String command, String arguments, List<FilePartInput> files) {
		JsonObject body = new JsonObject();
		body.addProperty("name", command);
		body.addProperty("text", arguments != null ? arguments : "");
		addFiles(body, files);
		return body;
	}

	private static void addFiles(JsonObject body, List<FilePartInput> files) {
		if (files == null || files.isEmpty()) return;
		JsonArray list = new JsonArray();
		for (FilePartInput file : files) {
			JsonObject f = new JsonObject(); f.addProperty("uri", file.url()); f.addProperty("name", file.filename());
			list.add(f);
		}
		body.add("files", list);
	}

	/** Apply the agent and model selection to the session before a prompt; V2 keeps them per session. */
	private void applySelection(String sid, String agent, String model, String variant) throws IOException {
		if (agent != null && !agent.isBlank() && !agent.equals(appliedAgent)) {
			JsonObject body = new JsonObject(); body.addProperty("agent", agent);
			post("/api/session/" + enc(sid) + "/agent", body.toString());
			appliedAgent = agent;
		}
		String selected = model != null && !model.isBlank() ? model : overrideModel;
		String key = selected == null ? null : selected + "#" + (variant == null ? "" : variant);
		if (selected != null && !key.equals(appliedModel)) {
			JsonObject body = new JsonObject(); body.add("model", V2.modelRef(selected, variant));
			post("/api/session/" + enc(sid) + "/model", body.toString());
			appliedModel = key;
		}
	}

	/**
	 * Subscribe to SSE, send the prompt, and forward events for {@code sessionId} (and its
	 * subagent sessions) to {@code onEvent} until the session's execution ends. Blocking; run on a
	 * worker thread.
	 */
	public void sendPromptStreaming(String text, Consumer<OpenCodeEvent> onEvent,
			String sessionId, String agent) throws IOException, InterruptedException {
		sendPromptStreaming(text, onEvent, sessionId, agent, null, null);
	}

	public void sendPromptStreaming(String text, Consumer<OpenCodeEvent> onEvent,
			String sessionId, String agent, String model, String variant) throws IOException, InterruptedException {
		String body = promptBody(text, List.of(), List.of()).toString();
		streamRequest(sessionId, onEvent, agent, model, variant, sid -> jsonPost("/api/session/" + enc(sid) + "/prompt", body));
	}

	public void executeCommandStreaming(String command, String arguments, Consumer<OpenCodeEvent> onEvent,
			String sessionId, String agent) throws IOException, InterruptedException {
		executeCommandStreaming(command, arguments, onEvent, sessionId, agent, null, null, List.of());
	}

	/** Run a command, or a skill when {@code command} names one (V2 attaches skills to a prompt). */
	public void executeCommandStreaming(String command, String arguments, Consumer<OpenCodeEvent> onEvent,
			String sessionId, String agent, String model, String variant, List<FilePartInput> files)
			throws IOException, InterruptedException {
		boolean skill = isSkill(command);
		String body = skill
				? promptBody(arguments == null || arguments.isBlank() ? "/" + command : arguments, files, List.of(command)).toString()
				: commandBody(command, arguments, files).toString();
		String endpoint = skill ? "/prompt" : "/command";
		streamRequest(sessionId, onEvent, agent, model, variant,
				sid -> jsonPost("/api/session/" + enc(sid) + endpoint, body));
	}

	private boolean isSkill(String name) {
		try {
			return listCommands().stream().anyMatch(c -> c.name().equals(name) && "skill".equals(c.source()));
		} catch (IOException e) {
			return false;
		}
	}

	private HttpRequest jsonPost(String path, String body) {
		return request(path).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
	}

	/** Keep session metadata current even while no prompt stream is active. */
	public synchronized void watchSessionEvents(Consumer<OpenCodeEvent> onEvent) {
		if (eventThread != null && eventThread.isAlive()) return;
		eventThread = new Thread(() -> {
			AtomicReference<InputStream> watched = new AtomicReference<>();
			try {
				HttpResponse<InputStream> response = http.send(request("/api/event")
						.header("Accept", "text/event-stream").GET().build(), BodyHandlers.ofInputStream());
				if (response.statusCode() != 200) return;
				InputStream stream = response.body(); watched.set(stream); eventStream.set(stream);
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						JsonObject raw = parseEvent(line);
						if (raw == null) continue;
						OpenCodeEvent ev = watchedEvent(raw);
						if (ev != null) onEvent.accept(ev);
					}
				}
			} catch (Exception ignored) {
				// Server disposal closes the stream; the next view starts a new watcher.
			} finally { eventStream.compareAndSet(watched.get(), null); }
		}, "opencode-session-events");
		eventThread.setDaemon(true); eventThread.start();
	}

	private OpenCodeEvent watchedEvent(JsonObject raw) {
		String type = optString(raw, "type", "");
		JsonObject data = raw.getAsJsonObject("data");
		String sid = V2.eventSession(raw);
		JsonObject p = new JsonObject();
		if (sid != null) p.addProperty("sessionID", sid);
		switch (type) {
		case "session.created", "session.renamed", "session.moved", "session.archived", "session.unarchived",
				"session.metadata.updated" -> {
			JsonObject info = new JsonObject(); info.addProperty("id", sid);
			String parent = data == null ? null : optString(data, "parentID", null);
			if (parent != null) { info.addProperty("parentID", parent); sessionParents.put(sid, parent); }
			p.add("info", info);
			return V2.event(type.equals("session.created") ? "session.created" : "session.updated", p);
		}
		case "session.deleted" -> { return V2.event("session.deleted", p); }
		case "session.execution.started" -> { return V2.status(sid, "busy"); }
		case "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" -> {
			return V2.status(sid, "idle");
		}
		case "permission.asked" -> { return data == null ? null : V2.permission(data, eventDirectory(raw)); }
		default -> { return null; }
		}
	}

	private void streamRequest(String sessionId, Consumer<OpenCodeEvent> onEvent, String agent, String model,
			String variant, java.util.function.Function<String, HttpRequest> requestFactory)
			throws IOException, InterruptedException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) throw new IOException("No active session");
		PromptRun run = new PromptRun();
		if (prompts.putIfAbsent(sid, run) != null) throw new IOException("Session is already busy");

		boolean idle = false;
		try {
			applySelection(sid, agent, model, variant);
			Set<String> sessions = new HashSet<>();
			sessions.add(sid);
			for (JsonElement child : getSessionDescendants(sid)) {
				String childId = optString(child.getAsJsonObject(), "id", null);
				if (childId != null) sessions.add(childId);
			}
			HttpResponse<InputStream> resp = http.send(request("/api/event")
					.header("Accept", "text/event-stream").GET().build(), BodyHandlers.ofInputStream());
			if (resp.statusCode() != 200) throw new IOException("Event subscription failed: " + resp.statusCode());
			InputStream stream = resp.body();
			run.stream.set(stream);
			run.post = http.sendAsync(requestFactory.apply(sid), BodyHandlers.ofString(StandardCharsets.UTF_8));
			run.post.whenComplete((response, error) -> {
				if (error != null || response.statusCode() >= 400) {
					String message = error != null ? error.getMessage()
							: "opencode prompt -> " + response.statusCode() + ": " + response.body();
					run.failure.set(new IOException(message, error));
					close(run.stream.get());
				}
			});

			StreamState state = new StreamState(onEvent);
			try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					JsonObject raw = parseEvent(line);
					if (raw == null) continue;
					String evSid = V2.eventSession(raw);
					if (evSid == null || !belongsTo(evSid, sessions)) continue;
					if (handleEvent(raw, evSid, state, eventDirectory(raw)) && sid.equals(evSid)) {
						state.flush();
						onEvent.accept(V2.status(sid, "idle"));
						idle = true;
						break;
					}
					state.flushIfDue();
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

	/** True when {@code sessionId} is the root or one of its (possibly newly spawned) descendants. */
	private boolean belongsTo(String sessionId, Set<String> sessions) {
		if (sessions.contains(sessionId)) return true;
		String current = sessionId;
		for (int depth = 0; depth < 8 && current != null; depth++) {
			String parent = sessionParents.get(current);
			if (parent == null) {
				try {
					parent = optString(getSession(current), "parentID", null);
				} catch (IOException e) {
					return false;
				}
				if (parent == null) { sessionParents.put(current, ""); return false; }
			}
			if (parent.isEmpty()) return false;
			if (sessions.contains(parent)) { sessions.add(sessionId); return true; }
			current = parent;
		}
		return false;
	}

	/** Pending message refreshes; fetching the message keeps rendering correct without replaying deltas. */
	private final class StreamState {
		final Consumer<OpenCodeEvent> onEvent;
		final java.util.LinkedHashMap<String, String> dirty = new java.util.LinkedHashMap<>();
		long lastFlush;

		StreamState(Consumer<OpenCodeEvent> onEvent) { this.onEvent = onEvent; }

		/** Streamed text per message, keyed by "text:ordinal"/"reasoning:ordinal"; stored messages lag behind. */
		final java.util.HashMap<String, java.util.HashMap<String, StringBuilder>> streamed = new java.util.HashMap<>();

		void mark(String sessionId, String messageId) {
			if (messageId != null) dirty.put(messageId, sessionId);
		}

		void delta(String kind, JsonObject data) {
			String messageId = optString(data, "assistantMessageID", null);
			String delta = optString(data, "delta", null);
			if (messageId == null || delta == null) return;
			streamed.computeIfAbsent(messageId, ignored -> new java.util.HashMap<>())
					.computeIfAbsent(kind + ":" + optString(data, "ordinal", "0"), ignored -> new StringBuilder()).append(delta);
		}

		void flushIfDue() {
			if (!dirty.isEmpty() && System.currentTimeMillis() - lastFlush >= REFRESH_INTERVAL_MS) flush();
		}

		void flush() {
			lastFlush = System.currentTimeMillis();
			var pending = new java.util.LinkedHashMap<>(dirty);
			dirty.clear();
			pending.forEach((messageId, sessionId) -> emitMessage(sessionId, messageId, onEvent, streamed.get(messageId)));
		}
	}

	private void emitMessage(String sessionId, String messageId, Consumer<OpenCodeEvent> onEvent,
			java.util.Map<String, StringBuilder> streamed) {
		try {
			JsonElement m = data(get("/api/session/" + enc(sessionId) + "/message/" + enc(messageId)));
			if (m == null || !m.isJsonObject()) return;
			JsonObject message = V2.message(m.getAsJsonObject(), sessionId);
			if (message == null) return;
			if (streamed != null) V2.overlayStreamedText(message.getAsJsonArray("parts"), streamed);
			JsonObject info = new JsonObject(); info.add("info", message.getAsJsonObject("info"));
			onEvent.accept(V2.event("message.updated", info));
			for (JsonElement part : message.getAsJsonArray("parts")) {
				JsonObject p = new JsonObject(); p.add("part", part);
				onEvent.accept(V2.event("message.part.updated", p));
			}
		} catch (IOException ignored) {
			// The next refresh or the final history reload shows the message.
		}
	}

	/** Dispatch one V2 event; returns true when the session's execution ended. */
	private boolean handleEvent(JsonObject raw, String sessionId, StreamState state, String directory) {
		String type = optString(raw, "type", "");
		JsonObject data = raw.getAsJsonObject("data");
		String messageId = optString(data, "assistantMessageID", null);
		switch (type) {
		case "session.text.delta", "session.reasoning.delta" -> {
			state.delta(type.equals("session.text.delta") ? "text" : "reasoning", data);
			state.mark(sessionId, messageId);
		}
		case "session.step.started", "session.text.started", "session.text.ended",
				"session.reasoning.started", "session.reasoning.ended",
				"session.tool.input.started", "session.tool.input.ended", "session.tool.called",
				"session.tool.progress", "session.tool.success", "session.tool.failed", "session.step.streamed" ->
			state.mark(sessionId, messageId);
		case "session.step.ended" -> {
			state.mark(sessionId, messageId);
			state.flush();
			for (JsonElement file : V2.stepFiles(data, directory)) {
				JsonObject p = new JsonObject(); p.addProperty("file", file.getAsString());
				state.onEvent.accept(V2.event("file.edited", p));
			}
		}
		case "session.step.failed", "session.execution.failed" -> {
			JsonObject error = data == null ? null : data.getAsJsonObject("error");
			String message = error != null ? optString(error, "message", type) : optString(data, "message", null);
			state.flush();
			if (message != null) state.onEvent.accept(V2.error(sessionId, message));
			return type.equals("session.execution.failed");
		}
		case "session.execution.started" -> state.onEvent.accept(V2.status(sessionId, "busy"));
		case "session.execution.succeeded", "session.execution.interrupted" -> {
			state.flush();
			state.onEvent.accept(V2.status(sessionId, "idle"));
			return true;
		}
		case "permission.asked" -> { if (data != null) state.onEvent.accept(V2.permission(data, directory)); }
		case "form.created" -> {
			JsonObject form = data == null ? null : data.getAsJsonObject("form");
			OpenCodeEvent question = form == null ? null : questionEvent(form);
			if (question != null) state.onEvent.accept(question);
		}
		default -> { }
		}
		return false;
	}

	private static String eventDirectory(JsonObject raw) {
		return optString(raw.getAsJsonObject("location"), "directory", null);
	}

	private static JsonObject parseEvent(String line) {
		if (!line.startsWith("data:")) return null;
		String json = line.substring(5).trim();
		if (json.isEmpty()) return null;
		try {
			return JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void rememberParent(JsonObject session) {
		String id = optString(session, "id", null);
		if (id != null) sessionParents.put(id, optString(session, "parentID", ""));
	}

	static boolean isForwardableEvent(OpenCodeEvent event, String rootSessionId, Set<String> childSessionIds) {
		String eventSessionId = event.sessionID();
		if (eventSessionId == null) return "file.edited".equals(event.type());
		return rootSessionId.equals(eventSessionId) || childSessionIds.contains(eventSessionId);
	}

	// ---- permissions / revert / abort ------------------------------------

	/** reply: "once" | "always" | "reject". */
	public void respondToPermission(String permissionId, String response) throws IOException {
		respondToPermission(permissionId, response, null);
	}

	/** Reply to a permission request; V2 addresses it through its session. */
	public void respondToPermission(String permissionId, String response, String directory) throws IOException {
		String session = permissionSession(permissionId, directory);
		JsonObject body = new JsonObject();
		body.addProperty("decision", response);
		post("/api/session/" + enc(session) + "/permission/" + enc(permissionId) + "/reply", body.toString());
	}

	private String permissionSession(String permissionId, String directory) throws IOException {
		String scope = directory != null && !directory.isBlank() ? directory : activeDirectory();
		JsonElement list = data(get(withLocation("/api/permission/request", scope)));
		if (list != null && list.isJsonArray()) for (JsonElement e : list.getAsJsonArray()) {
			JsonObject request = e.getAsJsonObject();
			if (permissionId.equals(optString(request, "id", null))) return optString(request, "sessionID", null);
		}
		String current = currentSessionId.get();
		if (current == null) throw new IOException("Unknown permission request " + permissionId);
		return current;
	}

	/** Stage a revert to just before {@code messageId}. */
	public JsonObject revertToMessage(String sessionId, String messageId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("messageID", messageId);
		post("/api/session/" + enc(sessionId) + "/revert/stage", body.toString());
		return getSession(sessionId);
	}

	public void abortSession(String sessionId) throws IOException {
		String sid = sessionId != null ? sessionId : currentSessionId.get();
		if (sid == null) throw new IOException("No active session to abort");
		try {
			post("/api/session/" + enc(sid) + "/interrupt", "{}");
		} finally {
			PromptRun run = prompts.get(sid);
			if (run != null) { run.cancelled = true; cancelRun(run); }
		}
	}

	private void abortQuietly(String sessionId) {
		try { post("/api/session/" + enc(sessionId) + "/interrupt", "{}"); } catch (IOException ignored) { }
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

	private static JsonElement data(JsonElement response) {
		return V2.data(response);
	}

	private JsonElement get(String path) throws IOException {
		return send(request(path).GET().build());
	}

	private JsonElement post(String path, String body) throws IOException {
		return post(path, body, Duration.ofSeconds(30));
	}

	private JsonElement post(String path, String body, Duration timeout) throws IOException {
		return send(jsonPost(path, body), timeout);
	}

	private JsonElement patch(String path, String body) throws IOException {
		return send(request(path).header("Content-Type", "application/json")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
	}

	private JsonElement delete(String path) throws IOException {
		return send(request(path).DELETE().build());
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

	private HttpRequest.Builder request(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
		if (authorization != null) builder.header("Authorization", authorization);
		return builder;
	}

	private URI uri(String path) {
		if (baseUrl == null) throw new IllegalStateException("OpenCode not initialized");
		return URI.create(baseUrl + path);
	}

	private static JsonObject location(String directory) {
		JsonObject location = new JsonObject(); location.addProperty("directory", directory); return location;
	}

	/** Append the deepObject {@code location[directory]} query used by location-scoped V2 endpoints. */
	static String withLocation(String path, String directory) {
		if (directory == null || directory.isBlank()) return path;
		return path + (path.contains("?") ? "&" : "?") + "location%5Bdirectory%5D=" + enc(directory);
	}

	private static String canonicalDirectory(String directory) throws IOException {
		if (directory == null || directory.isBlank()) throw new IOException("Session directory must not be blank");
		try {
			return java.nio.file.Path.of(directory).toRealPath().toString();
		} catch (java.nio.file.InvalidPathException e) {
			throw new IOException("Invalid session directory: " + directory, e);
		}
	}

	private String activeDirectory() {
		return currentSessionDirectory != null ? currentSessionDirectory : workspaceDir;
	}

	private static String enc(String s) {
		return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String optString(JsonObject o, String key, String dflt) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()
				? o.get(key).getAsString() : dflt;
	}
}
