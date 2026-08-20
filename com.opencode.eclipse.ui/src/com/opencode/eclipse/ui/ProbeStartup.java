package com.opencode.eclipse.ui;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Test-only hook: when the whole-view probe launch config sets
 * {@code -Dopencode.wholeViewProbe=true}, automatically open the primary OpenCode Chat view on
 * startup so {@link ChatView#startServerAsync()}'s probe trigger can run without requiring
 * manual interaction. No effect in normal use (the system property is never set otherwise).
 */
public final class ProbeStartup implements IStartup {
	@Override public void earlyStartup() {
		if (!Boolean.getBoolean("opencode.wholeViewProbe")) return;
		Display.getDefault().asyncExec(() -> {
			try {
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window != null) window.getActivePage().showView(ChatView.ID);
			} catch (Exception e) {
				System.err.println("[OpenCodeProbe] FAIL opening chat view on startup: " + e);
			}
		});
	}
}
