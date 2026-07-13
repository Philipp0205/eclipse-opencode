package com.opencode.eclipse.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.PlatformUI;

/** Opens and focuses the OpenCode chat view. */
public final class OpenChatHandler extends AbstractHandler {
	@Override public Object execute(ExecutionEvent event) throws ExecutionException {
		try {
			PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(ChatView.ID);
			return null;
		} catch (Exception e) { throw new ExecutionException("Failed to open OpenCode Chat", e); }
	}
}
