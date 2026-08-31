# OpenCode for Eclipse

An Eclipse plugin that embeds [opencode](https://opencode.ai) AI chat, backed by
a local `opencode serve` process. There is no Java OpenCode SDK, so the plugin
uses OpenCode's HTTP and SSE APIs directly through the JDK `HttpClient`.

## Features

- Streaming agent chat with Build and Plan agents and provider/model selection.
- Rich Browser-based conversation rendering: Markdown, tables, code blocks,
  reasoning, tool calls, dark/light themes, and long histories.
- The active editor is attached automatically; **All open tabs** optionally includes every editor, including selected
  text, unsaved content, and Eclipse problem markers.
- Paperclip attachment picker for Eclipse workspace files and folders.
- OpenCode slash commands, custom commands, MCP prompts, and skills with fuzzy
  completion. Eclipse-owned commands include `/models`, `/agents`, `/sessions`,
  `/new`, `/compact`, `/connect`, `/mcps`, `/permissions`, and `/help`.
- Messages entered while OpenCode is busy are queued locally and dispatched in
  order. Queued messages can be removed before they run.
- Permission and question dialogs, subagent rendering, and session
  rename/delete/fork/share/unrevert actions. Permission prompts offer
  **Always**, **Once**, and **Never**; Always and Never are remembered per
  action and directory and reused in later sessions until `/permissions`
  forgets them.
- Changed-file review with Eclipse Compare, Keep All, and per-file Undo.
- New Session and Settings view-toolbar actions plus an OpenCode status-trim
  button that opens the chat.
- Agent, model, activity, session cost, context percentage, and working folder
  share one compact row below the prompt; the selectable Info dialog shows token
  use, context limit, session ID, full folder, cost, and MCP count.
- Provider setup detection and `/connect` for OAuth or API-key authentication.

## Requirements

- `opencode` on `PATH` (the plugin starts a private loopback server on a reserved port).
- Eclipse 2025-03+, Java 21 (build target platform requires it).
- Linux requires WebKitGTK for the SWT Browser conversation view.

OpenCode must have at least one connected model provider. If it does not, the
view displays setup guidance and `/connect` opens the provider authentication
flow.

## Use

Open the view with *Window → Show View → Other → OpenCode → OpenCode Chat* or
click the OpenCode icon in Eclipse's bottom-right status trim.

- Enter sends a prompt; Shift+Enter inserts a newline.
- Alt+Up/Alt+Down traverses prompt history.
- Type `/` to search server and Eclipse slash commands.
- The paperclip searches workspace resources; all currently open editor files
  are attached according to the active/all-tabs setting unless removed from the chip row.
  The chip row is one row tall and scrolls; drag the sash above it to make it taller.
- The Settings toolbar button opens the global `opencode.json` in Eclipse's
  generic text editor. Its location follows `OPENCODE_CONFIG`, XDG on Linux,
  Application Support on macOS, and APPDATA on Windows.
- New sessions and session listing are scoped to the Eclipse workspace root.

## Build

```sh
JAVA_HOME=/path/to/jdk-21 ./mvnw verify
```

Produces a p2 update site at
`com.opencode.eclipse.repository/target/repository`. Install it into Eclipse via
*Help → Install New Software → Add → Local…*.

Before publishing an update, bump the semantic version in all POMs, both bundle
manifests, and `com.opencode.eclipse.feature/feature.xml`. Eclipse update checks
must not rely only on qualifier timestamps.

## GitHub Update Site

Install from *Help → Install New Software → Add…* using:

```text
https://philipp0205.github.io/eclipse-opencode/
```

The [GitHub Actions workflow](.github/workflows/publish-update-site.yml) builds the
Tycho p2 repository and deploys it to GitHub Pages after changes reach `master`.
Deployment replaces the complete Pages artifact, so obsolete feature and plugin
JARs are removed.

The workflow verifies that the semantic version is synchronized across all POMs,
bundle manifests, and `feature.xml`. If GitHub Pages already has the same semantic
version, it skips deployment rather than publishing a newer qualifier as a false
update. Bump the semantic plugin version whenever publishing a new build.

After publishing, Eclipse may retain cached p2 metadata. Refresh it under
*Preferences → Install/Update → Available Software Sites → Reload*, then use
*Help → Check for Updates*.

## Internal Update Site

`/home/phkurrle` is a company NFS share, so publish the repository to the stable
shared directory `/home/phkurrle/public/eclipse-opencode-update-site` when an
offline company-network mirror is needed.

Build and publish with:

```sh
JAVA_HOME=/path/to/jdk-21 ./mvnw clean verify
mkdir -p ~/public/eclipse-opencode-update-site
rsync -a --delete com.opencode.eclipse.repository/target/repository/ \
  ~/public/eclipse-opencode-update-site/
chmod -R a+rX ~/public/eclipse-opencode-update-site
```

Users install from *Help → Install New Software → Add* with:

```text
file:/home/phkurrle/public/eclipse-opencode-update-site
```

This only works on workstations that mount the same company home directory at
`/home/phkurrle`. If a URL is required instead, publish through company web
infrastructure with a company-trusted HTTPS certificate; plain HTTP or a
self-signed certificate requires client-side Eclipse configuration.

Removing and re-adding a site also clears Eclipse's cache, but Reload is
preferable.

## Test

```sh
DISPLAY=:1 test/run.sh
```

This builds the plugin and tests Markdown/tool rendering, model search, and
popup/queue/diff behavior and the actual SWT Browser DOM with a 300-message
conversation. Run the slower live OpenCode tests too with
`OPENCODE_LIVE_TESTS=1 DISPLAY=:1 test/run.sh`.

For an end-to-end test of the actual Eclipse view, run the Eclipse Application
launch configuration `opencode-whole-view-probe`. It opens the real view and
checks agent/model/status initialization, immediate user rendering, a live
assistant response, file edit/Compare, queued messages, abort, and continuation
in the same session. Success is reported as `[OpenCodeProbe] WHOLE VIEW OK` in
the launch console.

## Run in the IDE

Import the projects, then launch `com.opencode.eclipse.ui/launch/opencode.launch` (an Eclipse
Application). Open the view via *Window → Show View → Other → OpenCode → OpenCode
Chat*.

## Modules

| Bundle | Purpose |
|--------|---------|
| `com.opencode.eclipse.core` | `OpenCodeService` — server lifecycle + REST/SSE client |
| `com.opencode.eclipse.ui`   | Eclipse view, Browser renderer, context, dialogs, and review UI |
| `com.opencode.eclipse.feature` / `.repository` | feature + p2 update site |
| `com.opencode.eclipse.target` | Eclipse, JDT, Gson, and probe-runtime dependencies |

## Notes

- HTTP/1.1 is forced; the JDK client's HTTP/2 upgrade hangs against the server.
- One `opencode` process per view, killed on dispose.
- Conversations are rendered in one SWT `Browser`; Linux requires WebKitGTK.
- OpenCode's queued prompts are client-local, so the Eclipse plugin maintains
  its own in-memory FIFO and drains it after the active response ends.
- Server `/command` results do not include TUI commands such as model/session
  pickers; equivalent Eclipse client commands are added locally.

See [docs/architecture.md](docs/architecture.md) for protocol and UI design
details and [docs/api-gaps.md](docs/api-gaps.md) for the current OpenCode API
gap audit.
