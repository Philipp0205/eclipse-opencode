package com.opencode.eclipse.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Selectable OpenCode session information. */
final class InfoDialog extends Dialog {
	private final String text;
	InfoDialog(Shell parent, String text) { super(parent); this.text = text; }
	@Override protected void configureShell(Shell shell) { super.configureShell(shell); shell.setText("OpenCode session info"); }
	@Override protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		Text value = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		value.setText(text);
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true); data.widthHint = 520; data.heightHint = 220;
		value.setLayoutData(data);
		return area;
	}
}
