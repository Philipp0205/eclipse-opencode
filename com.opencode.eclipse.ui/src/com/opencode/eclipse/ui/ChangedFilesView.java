package com.opencode.eclipse.ui;

import java.nio.file.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.ui.part.ViewPart;

/** A quiet, workbench-native /diff surface: select a file, then compare it. */
public final class ChangedFilesView extends ViewPart {
	public static final String ID = "com.opencode.eclipse.ui.changedFilesView";
	private List files;
	private Diffs model;
	@Override public void createPartControl(org.eclipse.swt.widgets.Composite parent) {
		parent.setLayout(new GridLayout(2, false));
		new Label(parent, SWT.NONE).setText("Current session changes");
		new Label(parent, SWT.NONE).setText("Select a file to open Eclipse Compare");
		files = new List(parent, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
		files.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
		Button compare = new Button(parent, SWT.PUSH); compare.setText("Compare");
		compare.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));
		compare.addListener(SWT.Selection, e -> openSelected());
		files.addListener(SWT.DefaultSelection, e -> openSelected());
		refresh();
	}
	void bind(Diffs model) { this.model = model; refresh(); }
	private void refresh() {
		if (files == null || files.isDisposed()) return;
		files.removeAll();
		for (String path : model == null ? java.util.List.<String>of() : model.currentFiles()) files.add(Path.of(path).getFileName().toString() + "\t" + path);
		displayedPaths = model == null ? java.util.List.of() : model.currentFiles();
		if (files.getItemCount() > 0) files.select(0);
	}
	private void openSelected() {
		int i = files.getSelectionIndex();
		if (i >= 0 && i < displayedPaths.size()) model.openCompare(displayedPaths.get(i));
	}
	private java.util.List<String> displayedPaths = java.util.List.of();
	@Override public void setFocus() { files.setFocus(); }
}
