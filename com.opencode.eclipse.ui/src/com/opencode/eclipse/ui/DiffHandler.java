package com.opencode.eclipse.ui;
import org.eclipse.core.commands.*;
public final class DiffHandler extends AbstractHandler {
	@Override public Object execute(ExecutionEvent event) {
		var page = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		if (page != null && page.getActivePart() instanceof ChatView chat) chat.showDiffs();
		return null;
	}
}
