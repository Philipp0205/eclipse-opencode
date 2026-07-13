# Architecture

## Runtime

Each `ChatView` owns one `OpenCodeService`. The service starts:

```text
opencode serve --port 0 --hostname 127.0.0.1
```

in the Eclipse workspace root, parses the announced URL, checks
`/global/health`, and calls the OpenCode HTTP/SSE API using the JDK HTTP client.
HTTP/1.1 is forced because the server and JDK client can hang during an h2c
upgrade.

Session create/list/get requests include the workspace directory. New sessions
therefore run in the Eclipse workspace root and unrelated projects do not appear
in the session selector. The selector shows titles only; session ID and full
directory are available in the selectable Info dialog.

## Streaming

`OpenCodeService` maintains at most one prompt run per session. A run:

1. Opens `/event` before submitting the prompt or slash command.
2. Sends the request asynchronously.
3. Closes SSE immediately if the POST fails.
4. Filters events by captured session ID.
5. Ends on `session.status` with `idle`; deprecated `session.idle` remains a
   compatibility fallback.
6. Cancels its request/stream independently from other sessions.

`ChatView` receives events on the SWT thread. Session switching/new-session
creation is blocked while a response or queued prompt exists.

## Conversation Rendering

One SWT `Browser` renders the entire conversation. `ConversationHtml` creates
safe structured HTML; `ConversationBrowser` updates message DOM blocks by stable
message IDs. This design supports long histories and avoids GTK's native-child
coordinate limit encountered with one `StyledText` per message.

The renderer supports message cards, Markdown, tables, code, reasoning, tools,
todos, dark/light themes, pinned auto-scroll, welcome/setup states, and a live
Thinking card. Plaintext reasoning is expanded while streaming and collapsed on
completion; encrypted provider reasoning cannot be displayed.

## Commands And Queue

`GET /command` supplies project commands, MCP prompts, and skills. Selected
commands execute via `/session/{id}/command`; templates and arguments are always
resolved by OpenCode.

OpenCode TUI commands are not returned by `/command`. Eclipse supplies local
equivalents such as `/models`, `/agents`, `/sessions`, `/new`, `/compact`,
`/connect`, `/mcps`, and `/help`.

OpenCode's queue is client-local. Eclipse stores an in-memory FIFO containing an
immutable snapshot of prompt text, agent, and attachments, displays queued cards,
and dispatches the next message after completion or abort.

## Context And Edits

All open workspace and external editor files are attached by default. Context
also includes active selection, dirty editor content, and Eclipse problem
markers. Users can add workspace files/folders through a filtered Eclipse
resource picker and remove attachments from the chip row.

Edit/write tool targets are snapshotted before modification when visible.
Tracked files fall back to Git `HEAD`; new files use an empty before-state.
`file.edited` events populate the changed-files bar and each pending change opens
once in Eclipse Compare when the turn becomes idle. Completion also fetches
authoritative `/session/{id}/diff` data so missed events still open review. Keep
All and per-file Undo are available.

## Setup And Configuration

`GET /provider` determines whether OpenCode has a connected provider. `/connect`
uses OpenCode's provider auth APIs for OAuth and API keys. The Settings toolbar
action opens the global `opencode.json` in Eclipse's default text editor. Path
resolution follows `OPENCODE_CONFIG`, XDG, macOS Application Support, or Windows
APPDATA.

The compact status line shows cost, context percentage, and session folder. The
selectable Info dialog contains detailed tokens, context limit, session ID, full
folder, cost, and MCP count.

## Testing

- `DISPLAY=:1 test/run.sh`: build, pure rendering/model/queue tests, popup tests,
  diff/undo tests, and a real 300-message SWT Browser DOM test.
- `OPENCODE_LIVE_TESTS=1 DISPLAY=:1 test/run.sh`: additionally starts real
  OpenCode servers and models.
- `opencode-whole-view-probe`: instrumented Eclipse Application covering startup,
  status/defaults, live prompts, edits/Compare, queueing, abort, and continuation.
- Run JDT diagnostics after Java changes against `opencode-eclipse-ws`.
