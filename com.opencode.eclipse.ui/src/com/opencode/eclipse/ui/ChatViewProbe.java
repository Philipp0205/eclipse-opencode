package com.opencode.eclipse.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.eclipse.ui.IWorkbenchPage;

/**
 * Opt-in runtime probe for the whole {@link ChatView}. It drives the same widget/service
 * methods as user actions and validates the Browser DOM after each stage.
 *
 * <p>Split out of {@code ChatView} itself so the production class only contains chat
 * behavior; this class is test-support code, enabled only via the
 * {@code opencode.wholeViewProbe} system property (see {@code opencode-whole-view-probe.launch}).
 * It touches several {@code ChatView} members that are package-private specifically to support
 * this probe.
 */
final class ChatViewProbe {
	private final ChatView view;

	ChatViewProbe(ChatView view) {
		this.view = view;
	}

	void run() {
		// First, and independent of everything below: startup only gets here once the health
		// handshake succeeded, so a missing version means the server answered something the
		// plugin no longer understands. The output line records which port it actually took.
		probe(view.service.getServerVersion() != null, "opencode version reported by health check");
		System.out.println("[OpenCodeProbe] server: " + view.service.serverOutput().replace('\n', ' '));
		probe("build".equals(view.selectedAgentName()), "build agent selected");
		probe(view.modelButton.getText() != null && !view.modelButton.getText().isBlank(), "model selected");
		probe(view.status.getText().contains("$")
				&& view.status.getText().contains(Path.of(view.workingFolder).getFileName().toString()),
				"status cost and folder visible");
		probe(view.status.getText().contains("context"), "status context percentage visible");
		probe(view.statusBarIsSingleRow(), "agent, model and status share one row");
		probe(view.attachedAreaIsBounded(), "attached files area is bounded");
		probe(view.attachedAreaStaysOneRowWithManyTabs(), "many attachments scroll instead of growing");
		probe(OpenSettingsHandler.configPath().endsWith(Path.of("opencode", "opencode.json")),
				"settings path resolved");
		try {
			OpenSettingsHandler.open();
			var editor = view.getSite().getPage().getActiveEditor();
			probe(editor != null && OpenSettingsHandler.EDITOR_ID.equals(editor.getSite().getId()),
					"settings opened in Eclipse text editor");
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		probeMultipleViews(() -> {
			// Independent of the file-edit/abort chain below so a flaky unrelated stage
			// never blocks verifying subagent-session visibility in the Sessions Explorer.
			probeSubagentExplorer();
			probeInputAndReply("WHOLE_VIEW_PROBE", this::probeFileEdit);
		});
	}

	private void probeMultipleViews(Runnable next) {
		try {
			var page = view.getSite().getPage();
			var secondary = page.showView(ChatView.ID, "whole-view-secondary", IWorkbenchPage.VIEW_CREATE);
			page.showView(SessionMonitorView.ID, null, IWorkbenchPage.VIEW_CREATE);
			probeEventually("multiple chat views", 30_000, () -> ChatViewRegistry.snapshot().size() >= 2, () -> {
				page.activate(secondary);
				probe(page.getActivePart() == secondary, "monitor target activates");
				page.hideView(secondary);
				next.run();
			});
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private void probeFileEdit() {
		Path file = Path.of(view.service.getWorkspaceRoot(), "whole_view_probe.txt");
		try {
			Files.writeString(file, "before\n");
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		view.input.setText("Edit whole_view_probe.txt so it contains exactly after");
		view.send();
		probeEventually("file edit", 240_000,
				() -> Files.exists(file) && readProbeFile(file).contains("after"),
				() -> probeEventually("file edit completes", 120_000, () -> !view.busy, this::probeAbortAndContinue));
	}

	private static String readProbeFile(Path file) {
		try {
			return Files.readString(file);
		} catch (Exception e) {
			return "";
		}
	}

	private void probeInputAndReply(String marker, Runnable next) {
		view.input.setText("Reply with exactly " + marker);
		view.send();
		probeEventually("immediate user card", 5_000,
				() -> browserTextContains("Reply with exactly " + marker),
				() -> probeEventually("assistant reply", 120_000,
						() -> !view.busy && browserTextContains(marker), next));
	}

	private void probeAbortAndContinue() {
		view.input.setText("Count slowly from 1 to 1000");
		view.send();
		view.input.setText("Reply with exactly WHOLE_VIEW_QUEUED");
		view.send();
		probe(view.promptQueue.size() == 1, "message queued while busy");
		probe(browserTextContains("Queued"), "queued card visible");
		view.conversation.getDisplay().timerExec(1500, () -> {
			view.abortAsync();
			probeEventually("abort completes", 15_000, () -> !view.busy, () -> {
				probeEventually("queued response", 120_000,
						() -> !view.busy && browserTextContains("WHOLE_VIEW_QUEUED"),
						() -> probeInputAndReply("WHOLE_VIEW_CONTINUED",
								() -> System.out.println("[OpenCodeProbe] WHOLE VIEW OK")));
			});
		});
	}

	/** Verifies subagent/child sessions show up nested under their parent in the Sessions
	 * Explorer (persists after completion) and live in the OpenCode Sessions monitor (only
	 * while running — added to investigate a report that they were not appearing in either). */
	private void probeSubagentExplorer() {
		try {
			view.getSite().getPage().showView(SessionsExplorerView.ID, null, IWorkbenchPage.VIEW_CREATE);
			view.getSite().getPage().showView(SessionMonitorView.ID, null, IWorkbenchPage.VIEW_CREATE);
		} catch (Exception e) {
			System.err.println("[OpenCodeProbe] FAIL sessions explorer/monitor opened: " + e);
			return;
		}
		view.input.setText("Use the task tool to delegate to the explore subagent: read every .java file "
				+ "directly under com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/ and summarize "
				+ "what each one does in one sentence. Do not do this yourself, delegate it.");
		view.send();
		// Short poll: the monitor row only exists while the subagent is non-terminal, so this
		// must win the race against the subagent finishing, unlike the explorer check below.
		probeEventually("subagent visible live in OpenCode Sessions monitor", 30_000,
				SessionMonitorView::anyChildRowVisible,
				() -> System.out.println("[OpenCodeProbe] SUBAGENT MONITOR OK"));
		probeEventually("subagent visible nested in sessions explorer", 60_000,
				SessionsExplorerView::anyChildSessionVisible,
				() -> System.out.println("[OpenCodeProbe] SUBAGENT EXPLORER OK"));
	}

	private boolean browserTextContains(String text) {
		Object value = view.conversation.evaluate(
				"return document.getElementById('conversation').innerText;");
		return value instanceof String string && string.contains(text);
	}

	private void probeEventually(String name, long timeoutMs, BooleanSupplier condition, Runnable success) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		Runnable check = new Runnable() {
			@Override
			public void run() {
				if (condition.getAsBoolean()) {
					System.out.println("[OpenCodeProbe] PASS " + name);
					success.run();
				} else if (System.currentTimeMillis() >= deadline) {
					System.err.println("[OpenCodeProbe] FAIL " + name);
				} else {
					view.conversation.getDisplay().timerExec(250, this);
				}
			}
		};
		check.run();
	}

	private static void probe(boolean condition, String name) {
		if (!condition) throw new AssertionError("Whole-view probe failed: " + name);
		System.out.println("[OpenCodeProbe] PASS " + name);
	}
}
