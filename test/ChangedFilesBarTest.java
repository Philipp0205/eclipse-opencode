package com.opencode.eclipse.ui;

import java.nio.file.Files;
import java.util.ArrayList;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

public final class ChangedFilesBarTest {
	public static void main(String[] args) throws Exception {
		var file = Files.createTempFile("opencode-review", ".txt");
		Files.writeString(file, "before");
		var reviewed = new ArrayList<String>();
		Diffs diffs = new Diffs(reviewed::add); diffs.snapshotIfAbsent(file.toString());
		Files.writeString(file, "after");
		Display display = new Display(); Shell shell = new Shell(display); shell.setLayout(new FillLayout());
		ChangedFilesBar bar = new ChangedFilesBar(shell, diffs); shell.open();
		bar.add(file.toString()); bar.reviewPending(); bar.reviewPending();
		assert reviewed.equals(java.util.List.of(file.toString())) : reviewed;
		shell.dispose(); display.dispose(); Files.deleteIfExists(file);
		System.out.println("CHANGED FILES REVIEW OK");
	}
}
