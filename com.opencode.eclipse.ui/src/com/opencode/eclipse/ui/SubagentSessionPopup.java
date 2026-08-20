package com.opencode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.google.gson.JsonArray;

/**
 * Lightweight, non-modal read-only popup showing a delegated subagent's live conversation, so
 * double-clicking a "↳ ..." child row in the Sessions monitor doesn't have to spin up a whole
 * new secondary {@link ChatView} just to look at what a subagent is doing. Reuses the owning
 * view's already-directory-scoped {@link com.opencode.eclipse.core.OpenCodeService}, since a
 * delegated child session always lives in the same directory as its parent.
 */
final class SubagentSessionPopup {
	private static final int POLL_INTERVAL_MS = 2_000;

	private SubagentSessionPopup() { }

	static void open(ChatView owner, String childId, String directory, String title) {
		Display display = Display.getCurrent();
		Shell shell = new Shell(display, SWT.SHELL_TRIM);
		shell.setText("Subagent: " + title);
		shell.setLayout(new FillLayout());
		shell.setSize(700, 600);
		ConversationBrowser browser = new ConversationBrowser(shell);
		boolean[] disposed = { false };
		shell.addListener(SWT.Dispose, e -> disposed[0] = true);
		Runnable[] poll = new Runnable[1];
		poll[0] = () -> {
			if (disposed[0] || shell.isDisposed()) return;
			new Thread(() -> {
				JsonArray messages;
				try {
					messages = owner.service.getMessages(childId, directory);
				} catch (Exception ex) {
					return; // transient fetch failure: just skip this poll, try again next tick
				}
				if (disposed[0]) return;
				display.asyncExec(() -> {
					if (!disposed[0] && !shell.isDisposed()) browser.setConversation(ChatView.normalizeMessages(messages));
				});
			}, "opencode-subagent-popup").start();
			if (!disposed[0] && !shell.isDisposed()) display.timerExec(POLL_INTERVAL_MS, poll[0]);
		};
		poll[0].run();
		shell.open();
	}
}
