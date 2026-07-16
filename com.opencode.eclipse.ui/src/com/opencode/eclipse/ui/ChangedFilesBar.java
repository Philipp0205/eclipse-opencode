package com.opencode.eclipse.ui;

import java.nio.file.Path;
import java.util.LinkedHashSet;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Label;

/** Compact review bar for files edited during the current turn. */
final class ChangedFilesBar extends Composite {
	private final Diffs diffs;
	private final LinkedHashSet<String> files = new LinkedHashSet<>();
	private final LinkedHashSet<String> reviewed = new LinkedHashSet<>();

	ChangedFilesBar(Composite parent, Diffs diffs) {
		super(parent, SWT.BORDER); this.diffs = diffs;
		setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		setLayout(new GridLayout(5, false));
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
		Combo selected = new Combo(this, SWT.READ_ONLY);
		String[] paths = files.toArray(String[]::new);
		selected.setItems(java.util.Arrays.stream(paths).map(path -> Path.of(path).getFileName().toString()).toArray(String[]::new));
		selected.select(0); selected.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Button review = new Button(this, SWT.PUSH | SWT.FLAT); review.setText("Review");
		review.addListener(SWT.Selection, e -> diffs.openCompare(paths[selected.getSelectionIndex()]));
		Button undo = new Button(this, SWT.PUSH | SWT.FLAT); undo.setText("Undo"); undo.addListener(SWT.Selection, e -> {
			String path = paths[selected.getSelectionIndex()];
			try { diffs.undo(path); files.remove(path); rebuild(); } catch (Exception ignored) { }
		});
		Button keep = new Button(this, SWT.PUSH | SWT.FLAT); keep.setText("Keep all"); keep.addListener(SWT.Selection, e -> reset());
		layout(true, true); getParent().layout(true, true);
	}
}
