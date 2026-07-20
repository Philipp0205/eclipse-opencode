package com.opencode.eclipse.ui;
import org.eclipse.core.commands.*;
public final class PromptEditorHandler extends AbstractHandler {
	@Override public Object execute(ExecutionEvent event) throws ExecutionException {
		try { var page = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			if (!(page.getActivePart() instanceof ChatView chat)) return null;
			page.openEditor(new UntitledPromptEditorInput(chat.promptTarget(), ""), UntitledPromptEditor.ID, true); return null;
		} catch (Exception e) { throw new ExecutionException("Failed to open prompt editor", e); }
	}
}
