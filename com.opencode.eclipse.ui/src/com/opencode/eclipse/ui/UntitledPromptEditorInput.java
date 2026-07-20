package com.opencode.eclipse.ui;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;
final class UntitledPromptEditorInput implements IEditorInput {
	final OpenEditors.PromptTarget target;
	final String seed;
	UntitledPromptEditorInput(OpenEditors.PromptTarget target, String seed) { this.target = target; this.seed = seed == null ? "" : seed; }
	@Override public <T> T getAdapter(Class<T> adapter) { return null; }
	@Override public boolean exists() { return false; }
	@Override public ImageDescriptor getImageDescriptor() { return null; }
	@Override public String getName() { return "Untitled prompt"; }
	@Override public IPersistableElement getPersistable() { return null; }
	@Override public String getToolTipText() { return "Prompt for OpenCode chat"; }
}
