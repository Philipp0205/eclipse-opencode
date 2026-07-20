package com.opencode.eclipse.ui;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.EditorPart;
public final class UntitledPromptEditor extends EditorPart {
	public static final String ID = "com.opencode.eclipse.ui.untitledPromptEditor";
	private Text text;
	private String cleanText = "";
	@Override public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(1, false));
		text = new Text(parent, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		text.setText(((UntitledPromptEditorInput) getEditorInput()).seed);
		cleanText = text.getText();
		text.addModifyListener(e -> firePropertyChange(PROP_DIRTY));
		Button submit = new Button(parent, SWT.PUSH); submit.setText("Submit to originating chat");
		submit.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		submit.addListener(SWT.Selection, e -> submit());
		text.setFocus();
	}
	private void submit() {
		if (text.getText().isBlank()) return;
		var input = (UntitledPromptEditorInput) getEditorInput();
		if (input.target == null) return;
		input.target.submit(text.getText());
		cleanText = text.getText();
		firePropertyChange(PROP_DIRTY);
		setPartName("Submitted prompt");
	}
	@Override public void init(org.eclipse.ui.IEditorSite site, org.eclipse.ui.IEditorInput input) { setSite(site); setInput(input); setPartName(input.getName()); }
	@Override public void doSave(org.eclipse.core.runtime.IProgressMonitor monitor) { }
	@Override public void doSaveAs() { }
	@Override public boolean isDirty() { return text != null && !text.getText().equals(cleanText); }
	@Override public boolean isSaveAsAllowed() { return false; }
	@Override public void setFocus() { text.setFocus(); }
}
