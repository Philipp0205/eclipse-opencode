# Repository Guide

- Treat this file as living documentation: update it when verified repository changes make its guidance stale or incomplete; remove obsolete instructions rather than preserving them.

## Build And Test

- Use JDK 21: `JAVA_HOME=/path/to/jdk-21 ./mvnw verify`.
- The full test harness is `DISPLAY=:1 test/run.sh`; it runs the Tycho build, pure Java renderer/model tests, and SWT tests when `DISPLAY` and SWT jars are available.
- `test/run.sh` looks for SWT under `/usr/share/dbeaver-*/plugins`. Override with `SWT_API` and `SWT_GTK`; override Gson with `GSON`.
- Live tests spawn real OpenCode servers and model requests: `OPENCODE_LIVE_TESTS=1 DISPLAY=:1 test/run.sh`. Do not enable them for routine verification.
- There is no separate lint, formatter, or Maven-managed test suite; tests under `test/` are compiled and run directly by `test/run.sh`.
- After Java edits, refresh the affected projects and run `jdt problems --workspace opencode-eclipse-ws`; do not rely only on Tycho compilation.
- If JDT suddenly reports every Eclipse/SWT/Gson type as missing while Tycho succeeds, treat it as a stale or unresolved PDE target, not hundreds of source errors. Open `com.opencode.eclipse.target/opencode.target`, set it as the active target, refresh the projects, then run clean JDT builds before diagnosing source code.
- Keep `opencode.target` portable. Do not add machine-local repositories, environment-variable repository URIs, or optional unrelated plugins to the shared target; one unresolved target location removes the complete PDE classpath in Eclipse.

### Agent Self-Test Workflow

1. Run `DISPLAY=:1 test/run.sh` after code changes. A successful complete run ends with `ALL TESTS OK`. If no display or SWT jars are available, the script reports that Browser smoke tests were skipped; do not describe that as full UI verification.
2. For Java changes, refresh the affected Eclipse projects and run `jdt problems --workspace opencode-eclipse-ws`. Resolve new errors and warnings in changed code.
3. Use `OPENCODE_LIVE_TESTS=1 DISPLAY=:1 test/run.sh` only when changes affect `OpenCodeService`, REST/SSE behavior, sessions, commands, abort/continue, or other live server interactions. These tests require `opencode` on `PATH`, a configured provider, network access, and make real model requests.
4. For `ChatView` lifecycle, SWT-threading, Browser DOM, file-edit review, queue, or abort behavior, run the Eclipse Application launch configuration `com.opencode.eclipse.ui/launch/opencode-whole-view-probe.launch`. It is successful only when the launch console prints `[OpenCodeProbe] WHOLE VIEW OK`; any `[OpenCodeProbe] FAIL` or missing completion is a failure.
5. **Mandatory for any non-trivial task touching UI/view behavior** (new views, view interactions, dialogs, session/explorer state, anything a `mvn verify` compile check cannot observe): drive and inspect the actual running **runtime probe instance** with its own EclipseExecutor MCP server, per "Runtime Instance MCP Verification" below. A green Tycho build only proves the code compiles — it does not prove the feature behaves correctly in a live workbench. Do not declare such a task done on compilation alone.

### Runtime Instance MCP Verification (mandatory for non-trivial UI changes)

The `opencode-whole-view-probe.launch` config starts a second, independent Eclipse process (self-hosted against this workspace's compiled classes — no jar rebuild/reinstall needed, just re-run the launch after saving source changes). Two separate EclipseExecutor MCP servers are involved and must not be confused:

- **Host/parent instance** — the real IDE you are developing in. Its EclipseExecutor server is already running per its own configured port (see `~/.eclipse-mcp-server/preferences.properties`, `mcpServerPort`; this repo's host is documented at `http://127.0.0.1:3001/mcp`). Use it only to *launch* the runtime instance (`execute_eclipse_command` with `org.eclipse.debug.ui.commands.OpenRunConfigurations`, then `get_active_dialog` with `closeAfterRead:true` — this reliably clicks the dialog's actual default button, which is "Run" once a valid configuration is selected; fuzzy `clickButton` text matching is unreliable here because "Run" is a substring of the "Run a product:"/"Run an application:" radio labels and the tool returns the first widget match in document order, not the true button).
- **Runtime/child instance** — the self-hosted `RuntimeWorkbench` process under test. It needs `io.github.advae.eclipsemcp.server` (or its current build id) included in its own bundle set and its own **distinct** port (EclipseExecutor's port/token file is per-OS-user, not per-workspace, so the child cannot share the host's port — set a different `mcpServerPort` before launching, e.g. `3002`, or the child's server silently fails to bind).

Setup once per environment:
1. Make sure the EclipseExecutor plugin is actually resolvable as a bundle for the **runtime** instance's own launch, without touching any shared/production workspace's active Target Platform: prefer dropping the built `com.eclipse.mcp.server`/`io.github.advae.eclipsemcp.server` jar directly into the exact directory a `Directory`-type location in the *active* target already scans (check the active `.target` file's `<location type="Directory">` `path=`; it is often the real Eclipse install's own `plugins/` folder, not a `dropins/` folder — dropins semantics are not guaranteed for PDE self-hosting resolution). Do not switch a shared workspace's active Target Platform to this project's own `opencode.target` just to pick up this one plugin — that breaks unrelated projects that depend on the workspace's real target platform. Reload (not switch) the already-correct target after adding the jar.
2. Add `io.github.advae.eclipsemcp.server@default:default` (or the current bundle id) to `selected_target_bundles` in `opencode-whole-view-probe.launch`.
3. Before each launch of the runtime instance, ensure its configured MCP port differs from the host's.
4. Launch via the host's Run Configurations dialog (see above) or, if `jdt`/JDT Bridge is connected to that same workspace, `jdt launch run opencode-whole-view-probe -q` (cleanly starts in Run mode, avoiding the Debug/`suspend=y` mode the dialog path can accidentally trigger).

Once the runtime instance's own MCP server responds on its configured port:
- `execute_eclipse_command` (`org.eclipse.ui.views.showView`) to open the view(s) under test.
- `take_screenshot` (optionally scoped to a `viewId`) to visually verify rendering.
- `execute_eclipse_command` / `trigger_eclipse_handler` to exercise plugin commands.
- `get_active_dialog`, `get_view_content`, `list_view_item_actions` to check dialogs/views render and behave correctly (note: custom SWT `Browser`-based views like `ChatView` are not text-extractable via `get_view_content`; use `take_screenshot` for those).
- `get_console_content` to catch runtime errors/exceptions the compiler cannot catch.

For probe-style regression checks specifically, prefer adding a new `probe*`/`probeEventually` stage in `ChatView.runWholeViewProbe()` (see `SessionsExplorerView.anyChildSessionVisible()` for a precedent: a package-private static introspection helper added purely for probe use) over ad-hoc MCP-only verification, since it is captured in the console log (`[OpenCodeProbe] PASS/FAIL <name>`) and reruns automatically on every future probe launch. Keep new probe stages independent of unrelated existing stages (call them from an early, reliable point in the chain, not nested after flaky prior stages) so one flaky stage does not mask a passing new one.

The whole-view probe must run from an Eclipse workspace with the PDE projects imported and target platform resolved. It starts a real OpenCode server, sends model prompts, creates `whole_view_probe.txt` in its runtime workspace, and exercises Eclipse Compare. The ordinary harness remains the default because it is deterministic apart from optional SWT availability; live tests and the probe are escalation checks, not routine tests.

## Local Dev Install After Non-Trivial Tasks

- After finishing any non-trivial task (multi-file change, bug fix, or feature — not a trivial one-line/typo edit), bump the semantic plugin version, build the jar, and auto-install the plugin into the local IDE at `~/.eclipse_ide/advantest_ide_438_RH9` via its `dropins`/`plugins` folder, without waiting for a full release.
- Bump the version consistently in the root/module Maven POMs, both bundle `META-INF/MANIFEST.MF` files, and `com.opencode.eclipse.feature/feature.xml` (same rule as the Releases section below).
- Build with `JAVA_HOME=/usr/lib/jvm/java-21 ./mvnw -q -DskipTests package`.
- Install locally by copying the built plugin jar from `com.opencode.eclipse.ui/target/*.jar` (and `com.opencode.eclipse.core/target/*.jar` if changed) into `~/.eclipse_ide/advantest_ide_438_RH9/dropins/plugins/` (or the IDE's `plugins` folder if it uses `dropins/eclipse/plugins` layout — check which exists first), replacing any older-versioned jar of the same bundle so the IDE picks up the new version on next start.
- This local install is separate from the public update-site release process below; it exists purely to let the running local IDE pick up in-progress changes quickly. Still perform the full Releases sequence when actually publishing.

## Releases

- Push this repository with the `Philipp0205` GitHub CLI account. Before release pushes, run `gh auth switch --hostname github.com --user Philipp0205`; the enterprise account does not have access to this repository.
- Always bump the semantic plugin version before publishing or updating the p2 update site; do not rely only on the generated qualifier timestamp because Eclipse update checks must see an unambiguous newer version.
- Keep the version synchronized in the root/module Maven POMs, both bundle `META-INF/MANIFEST.MF` files, and `com.opencode.eclipse.feature/feature.xml`, then run a clean repository build.
- Publish with `rsync -a --delete` (or an equivalent clean replacement) so stale feature/plugin JARs are removed. Verify the published `content.jar`, not only the build output.
- Release sequence: bump the semantic version, run `JAVA_HOME=/usr/lib/jvm/java-21 ./mvnw -q -DskipTests package`, then publish with `rsync -a --delete com.opencode.eclipse.repository/target/repository/ /home/phkurrle/public/eclipse-opencode-update-site/`.
- Confirm publication with `unzip -p /home/phkurrle/public/eclipse-opencode-update-site/content.jar content.xml` and check that it contains the new semantic version.
- Eclipse may cache p2 metadata. Advise users to Reload the existing Available Software Site before Check for Updates; re-adding the site is only a fallback.
- The internal published site is `/home/phkurrle/public/eclipse-opencode-update-site`. Building `com.opencode.eclipse.repository/target/repository` does not publish it; copy with deletion semantics and inspect the published `content.jar` for the new semantic version.

## Architecture

- This is a five-module Eclipse Tycho build. `com.opencode.eclipse.target/opencode.target` is the dependency source of truth; the root `pom.xml` targets Linux, macOS, and Windows.
- `com.opencode.eclipse.core/OpenCodeService` owns the `opencode serve` process and the REST/SSE protocol. It deliberately forces HTTP/1.1 because the JDK HTTP/2 upgrade hangs against OpenCode.
- `com.opencode.eclipse.ui/ChatView` owns SWT state and worker/UI-thread handoff. Conversation rendering flows through `ConversationHtml` into one SWT `Browser` managed by `ConversationBrowser`; its packaged page assets are in `resources/`.
- Multiple ChatView secondary instances each own an independent OpenCodeService/process. `SessionMonitorView` tracks live chat views and activates the selected one; status precedence is blocked, running, then done.
- Session creation/listing is scoped with OpenCode's `directory` query to the Eclipse workspace root. Preserve this when adding session endpoints.
- When `ENV_SCM_WORKSPACE_ROOT` is set, it replaces the Eclipse workspace root for the OpenCode process and all directory-scoped APIs, including the session list.
- The selected session's returned `directory` is the status-line working folder. Keep session IDs out of the selector; expose them only in the selectable Info dialog.
- OpenCode prompt runs are isolated per session. Prompt POST failures must close SSE; completion accepts current `session.status=idle` plus the legacy `session.idle` fallback.
- Only mutate ChatView conversation/session state on the SWT thread. Worker callbacks must marshal through `ui(...)` and validate the turn/session they belong to.
- Queued prompts are local immutable snapshots of text, selected agent, and attachments. OpenCode does not provide a durable server queue.
- `/command` is authoritative for project commands, skills, and MCP prompts. Eclipse-owned commands (`/models`, `/sessions`, `/compact`, etc.) are intentionally maintained separately; never expand OpenCode command templates locally.
- Slash completion must never steal focus from the prompt. Keep its popup non-focusable, update filtering from prompt Modify events, and commit keyboard selection through the prompt key listener.
- Internal OpenCode agents have `hidden=true`; never expose them in the agent selector.
- The Browser renderer is the only conversation renderer. Do not reintroduce one SWT control per message: GTK stops painting deep native child controls in long histories.
- File review snapshots edit/write tool targets before modification where possible, uses Git HEAD for tracked-file fallback, and opens Compare once per changed file at turn completion.
- At turn completion, fetch authoritative `GET /session/{id}/diff` data (scoped by directory) before reviewing changes. `file.edited` alone is insufficient because events or tool paths may be missing.
- Permission and question list/reply endpoints must include the workspace `directory` query. Question radio controls need a separate SWT parent per question so groups do not interfere.
- Permission prompts offer Always / Once / Never. `PermissionDecisions` persists Always and Never in Eclipse preferences keyed by a hash of directory + permission + patterns, and later matching requests are answered without a dialog; OpenCode's own "always" only lasts as long as its server process. Never persist "once" or a dismissed dialog, and keep `/permissions` as the way to forget stored answers.
- Global `opencode.json` is external to the Eclipse workspace. Resolve it through `OPENCODE_CONFIG`, XDG, macOS Application Support, or APPDATA, and explicitly open it with `org.eclipse.ui.DefaultTextEditor` using `FileStoreEditorInput`.
- The active editor is attached by default; **All open tabs** opt-in attaches every open workspace and external editor. Refresh attachment chips on editor open/close/activation; preserve selected text, dirty document contents, and Eclipse problem markers in queued prompt snapshots.
- The attachment chips live in a bounded `ScrolledComposite` that is one chip row tall by default, with a `Sash` above it for user resizing (persisted as `attachedFilesHeight`). Never let it grab excess vertical space — many open tabs would otherwise push the conversation off screen.
- Agent, model, activity, and session metrics share one compact row below the prompt. Keep it a single row with zero layout margins; do not reintroduce separate selector and status rows.
- Keep PDE metadata synchronized with code: bundle dependencies belong in `META-INF/MANIFEST.MF`, packaged files in `build.properties`, and views/commands/handlers in `plugin.xml`.
- The update site is emitted at `com.opencode.eclipse.repository/target/repository`.

## Runtime Checks

- The plugin requires `opencode` on `PATH`; each `OpenCodeService` instance starts `opencode serve --port 0 --hostname 127.0.0.1` in the Eclipse workspace and kills it on disposal.
- Provider setup is detected through `GET /provider`; no connected provider must produce setup guidance rather than a broken prompt.
- The status line shows `cost · context percent · session folder`. Session ID, full folder, token details, cost, and MCP count belong in the selectable Info dialog.
- The todo/task-list surface was removed deliberately (the panel could not be cleared once early todos completed). Do not reintroduce `TodoPanel`, `todo.updated` handling, `GET /session/{id}/todo`, or special `todowrite`/`todoread` rendering; those tool calls render as ordinary tool cards.
- Run the plugin with `com.opencode.eclipse.ui/launch/opencode.launch` after importing the PDE projects and resolving the target platform.
- The whole-view probe is `com.opencode.eclipse.ui/launch/opencode-whole-view-probe.launch`; success is `[OpenCodeProbe] WHOLE VIEW OK` in the launch console.
- Linux SWT Browser rendering requires WebKitGTK; without it the chat view displays a startup error.
