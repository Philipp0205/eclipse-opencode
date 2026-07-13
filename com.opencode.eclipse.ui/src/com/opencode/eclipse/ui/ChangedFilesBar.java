package com.opencode.eclipse.ui;

import java.nio.file.Path;
import java.util.LinkedHashSet;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/** Compact review bar for files edited during the current turn. */
final class ChangedFilesBar extends Composite {
	private final Diffs diffs;
	private final LinkedHashSet<String> files = new LinkedHashSet<>();
	private final LinkedHashSet<String> reviewed = new LinkedHashSet<>();

	ChangedFilesBar(Composite parent, Diffs diffs) {
		super(parent, SWT.BORDER); this.diffs = diffs;
		setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		setLayout(new GridLayout(3, false));
		setVisible(false); ((GridData) getLayoutData()).exclude = true;
	}

	void reset() { files.clear(); reviewed.clear(); rebuild(); }

	void add(String path) { diffs.snapshotIfAbsent(path); files.add(path); rebuild(); }

	/** Open each current change once when the turn finishes. */
	void reviewPending() {
		files.removeIf(path -> !diffs.changed(path));
		for (String path : files) if (reviewed.add(path)) diffs.openCompare(path);
		rebuild();
	}

	private void rebuild() {
		for (var child : getChildren()) child.dispose();
		files.removeIf(path -> !diffs.changed(path));
		boolean visible = !files.isEmpty(); setVisible(visible); ((GridData) getLayoutData()).exclude = !visible;
		if (!visible) { getParent().layout(true, true); return; }
		Label title = new Label(this, SWT.NONE); title.setText(files.size() + " changed file(s)");
		new Label(this, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Button keep = new Button(this, SWT.PUSH | SWT.FLAT); keep.setText("Keep all"); keep.addListener(SWT.Selection, e -> reset());
		for (String path : files) {
			Button file = new Button(this, SWT.PUSH | SWT.FLAT); file.setText(Path.of(path).getFileName().toString());
			file.setToolTipText(path); file.addListener(SWT.Selection, e -> diffs.openCompare(path));
			new Label(this, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			Button undo = new Button(this, SWT.PUSH | SWT.FLAT); undo.setText("Undo"); undo.addListener(SWT.Selection, e -> {
				try { diffs.undo(path); files.remove(path); rebuild(); } catch (Exception ignored) { }
			});
		}
		layout(true, true); getParent().layout(true, true);
	}
}
