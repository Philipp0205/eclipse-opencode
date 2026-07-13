package com.opencode.eclipse.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.graphics.Image;

/**
 * Opens Eclipse's built-in compare (diff) view for a file opencode edited,
 * showing the pre-edit snapshot (left) against the current on-disk content (right).
 *
 * <p>ponytail: reuses {@link CompareUI} — no custom diff widget. Snapshots are
 * captured lazily the first time a path is seen edited during a turn.
 */
final class Diffs {

	/** absolute path -> content captured before opencode's first edit this turn. */
	private final Map<String, String> snapshots = new ConcurrentHashMap<>();
	private final java.util.function.Consumer<String> reviewer;

	Diffs() { this(null); }
	Diffs(java.util.function.Consumer<String> reviewer) { this.reviewer = reviewer; }

	/** Forget all snapshots (call at the start of each prompt turn). */
	void reset() {
		snapshots.clear();
	}

	/** Record the current on-disk content as the "before" if not already captured. */
	void snapshotIfAbsent(String absolutePath) {
		snapshots.computeIfAbsent(absolutePath, path -> gitBefore(path));
	}

	boolean changed(String path) { return !snapshots.getOrDefault(path, gitBefore(path)).equals(readOrEmpty(path)); }

	void undo(String absolutePath) throws java.io.IOException {
		String before = snapshots.getOrDefault(absolutePath, gitBefore(absolutePath));
		Path path = Path.of(absolutePath);
		if (before.isEmpty() && !tracked(path)) Files.deleteIfExists(path);
		else { Files.createDirectories(path.getParent()); Files.writeString(path, before, StandardCharsets.UTF_8); }
	}

	/** Open a compare editor: snapshot (before) vs current file (after). Must run on UI thread. */
	void openCompare(String absolutePath) {
		String before = snapshots.getOrDefault(absolutePath, "");
		String after = readOrEmpty(absolutePath);
		if (before.equals(after)) {
			return; // nothing changed
		}
		if (reviewer != null) { reviewer.accept(absolutePath); return; }
		String name = Path.of(absolutePath).getFileName().toString();
		CompareConfiguration cfg = new CompareConfiguration();
		cfg.setLeftLabel(name + " (before opencode)");
		cfg.setRightLabel(name + " (after)");
		cfg.setLeftEditable(false);
		cfg.setRightEditable(false);
		CompareEditorInput input = new TextCompareInput(cfg, name,
				new StringElement(name, before), new StringElement(name, after));
		CompareUI.openCompareEditor(input);
		if (Boolean.getBoolean("opencode.wholeViewProbe")) {
			System.out.println("[OpenCodeProbe] PASS compare opened " + name);
		}
	}

	private static String readOrEmpty(String absolutePath) {
		try {
			Path p = Path.of(absolutePath);
			return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
		} catch (Exception e) {
			return "";
		}
	}

	private static String gitBefore(String absolutePath) {
		Path file = Path.of(absolutePath).toAbsolutePath();
		Path root = gitRoot(file.getParent());
		if (root == null) return readOrEmpty(absolutePath);
		try {
			String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
			Process process = new ProcessBuilder("git", "show", "HEAD:" + relative).directory(root.toFile()).start();
			String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return process.waitFor() == 0 ? value : "";
		} catch (Exception ignored) { return ""; }
	}

	private static boolean tracked(Path file) {
		Path root = gitRoot(file.toAbsolutePath().getParent());
		if (root == null) return false;
		try {
			String relative = root.relativize(file.toAbsolutePath()).toString();
			return new ProcessBuilder("git", "ls-files", "--error-unmatch", relative).directory(root.toFile())
					.start().waitFor() == 0;
		} catch (Exception ignored) { return false; }
	}

	private static Path gitRoot(Path start) {
		for (Path path = start; path != null; path = path.getParent()) if (Files.isDirectory(path.resolve(".git"))) return path;
		return null;
	}

	// ---- compare-model glue ----------------------------------------------

	private static final class TextCompareInput extends CompareEditorInput {
		private final ITypedElement left;
		private final ITypedElement right;
		private final String title;

		TextCompareInput(CompareConfiguration cfg, String title, ITypedElement left, ITypedElement right) {
			super(cfg);
			this.left = left;
			this.right = right;
			this.title = title;
		}

		@Override
		protected Object prepareInput(org.eclipse.core.runtime.IProgressMonitor monitor) {
			setTitle("Diff: " + title);
			return new DiffNode(Differencer.CHANGE, null, left, right);
		}
	}

	private static final class StringElement implements ITypedElement, IStreamContentAccessor {
		private final String name;
		private final String content;

		StringElement(String name, String content) {
			this.name = name;
			this.content = content;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Image getImage() {
			return null;
		}

		@Override
		public String getType() {
			return ITypedElement.TEXT_TYPE;
		}

		@Override
		public java.io.InputStream getContents() throws CoreException {
			return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		}
	}
}
