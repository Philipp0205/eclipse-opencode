package com.opencode.eclipse.ui;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DiffsTest {
	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("opencode-diff-test");
		new ProcessBuilder("git", "init", "-q").directory(root.toFile()).start().waitFor();
		new ProcessBuilder("git", "config", "user.email", "probe@example.com").directory(root.toFile()).start().waitFor();
		new ProcessBuilder("git", "config", "user.name", "Probe").directory(root.toFile()).start().waitFor();
		Path tracked = root.resolve("tracked.txt"); Files.writeString(tracked, "before\n");
		new ProcessBuilder("git", "add", ".").directory(root.toFile()).start().waitFor();
		new ProcessBuilder("git", "commit", "-qm", "base").directory(root.toFile()).start().waitFor();
		Diffs diffs = new Diffs();
		Files.writeString(tracked, "after\n"); diffs.snapshotIfAbsent(tracked.toString());
		assert diffs.changed(tracked.toString()); diffs.undo(tracked.toString());
		assert Files.readString(tracked).equals("before\n");
		Path created = root.resolve("new.txt"); Files.writeString(created, "new\n"); diffs.snapshotIfAbsent(created.toString());
		diffs.undo(created.toString()); assert !Files.exists(created);
		System.out.println("DIFFS OK");
	}
}
