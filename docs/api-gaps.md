# Remaining OpenCode API Gaps

Audited against OpenCode `1.17.18` (`GET /doc`) and the installed SDK types.
Core prompt streaming, current idle events, abort, permissions, questions,
commands, provider setup, session CRUD, file review, and MCP
status/connect/disconnect are implemented. Session todos are intentionally not
consumed: the panel could not be cleared reliably and was removed.

## High Value

- Attachments are text/path context. OpenCode `FilePartInput` is still needed for
  images and native multimodal attachments.
- MCP OAuth states (`needs_auth`, client registration, callback/authenticate)
  are not handled in the Eclipse MCP dialog.
- Session share exists, but unshare/revoke is not exposed in the session menu.
- OpenCode message revert exists in the service but has no per-message Browser
  action. Eclipse file Undo is separate from OpenCode history revert.
- Startup recovery handles only the first pending permission and question for a
  session; multiple pending requests should be queued.

## Reliability And UX

- `session.status=retry` and retry parts are not rendered, so transient provider
  retries can look like a long Thinking state.
- A transient SSE disconnect aborts the prompt instead of checking session status
  and resynchronizing messages.
- Message/part removal events are ignored.
- The current Markdown renderer is intentionally small and not full CommonMark.

## Intentionally Deferred

- OpenCode TUI endpoints are not used; Eclipse provides native model/session/help
  UI instead.
- PTY, experimental workspace/worktree, sync/control-plane, global upgrade, and
  console-organization APIs are outside this plugin's current scope.
- OpenCode file/search/LSP/formatter APIs mostly duplicate Eclipse-native resource,
  search, diagnostic, and formatter facilities.
