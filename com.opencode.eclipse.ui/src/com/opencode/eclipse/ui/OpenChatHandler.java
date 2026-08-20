package com.opencode.eclipse.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.PlatformUI;

/** Opens and focuses the OpenCode chat view. */
public final class OpenChatHandler extends AbstractHandler {
	@Override public Object execute(ExecutionEvent event) throws ExecutionException {
		try {
			org.eclipse.ui.IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			page.showView(ChatView.ID,
					java.util.UUID.randomUUID().toString(), org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
			try {
				page.showView(SessionMonitorView.ID, null, org.eclipse.ui.IWorkbenchPage.VIEW_VISIBLE);
			} catch (Exception ignored) {
				// The chat view is the primary action; an unavailable sessions view must not block it.
			}
			return null;
		} catch (Exception e) { throw new ExecutionException("Failed to open OpenCode Chat", e); }
	}
}
