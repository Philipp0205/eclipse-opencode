package com.opencode.eclipse.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.graphics.Image;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

/**
 * Opens Eclipse's built-in compare (diff) view for a file opencode edited,
 * showing the pre-edit snapshot (left) against the current on-disk content (right).
 *
 * <p>ponytail: reuses {@link CompareUI} — no custom diff widget. Snapshots are
 * captured lazily the first time a path is seen edited during a turn.
 */
final class Diffs {

	/** absolute path -> the local, pre-edit snapshot used by the legacy edit events. */
	private final Map<String, String> snapshots = new ConcurrentHashMap<>();
	/** The last server response.  This is intentionally independent of the workspace. */
	private final Map<String, ServerDiff> authoritative = new LinkedHashMap<>();
	private volatile boolean authoritativeLoaded;
	private final java.util.function.Consumer<String> reviewer;

	Diffs() { this(null); }
	Diffs(java.util.function.Consumer<String> reviewer) { this.reviewer = reviewer; }

	/** Files in the currently displayed turn. Used by the /diff workbench view. */
	List<String> currentFiles() { return files(); }

	private List<String> files() {
		synchronized (authoritative) {
			if (authoritativeLoaded) return authoritative.keySet().stream().sorted().toList();
		}
		return snapshots.keySet().stream().filter(this::changed).sorted().toList();
	}

	/** Forget all snapshots (call at the start of each prompt turn). */
	void reset() {
		snapshots.clear();
		synchronized (authoritative) { authoritative.clear(); }
		authoritativeLoaded = false;
	}

	/** Replace the turn model with the authoritative session diff. */
	void setAuthoritativeChanges(JsonArray changes, String workspaceRoot) {
		var serverDiffs = new LinkedHashMap<String, ServerDiff>();
		for (JsonElement element : changes) {
			if (!element.isJsonObject()) continue;
			var object = element.getAsJsonObject();
			String file = string(object, "file");
			if (file == null) continue;
			Path path = Path.of(file);
			if (!path.isAbsolute()) path = Path.of(workspaceRoot).resolve(path);
			String absolute = path.normalize().toString();
			serverDiffs.put(absolute, new ServerDiff(content(object, "before", "old", "beforeContent"),
					content(object, "after", "new", "afterContent"), string(object, "patch")));
		}
		synchronized (authoritative) {
			authoritative.clear();
			authoritative.putAll(serverDiffs);
		}
		authoritativeLoaded = true;
	}

	/** Opens the workbench listing; this is deliberately not a dialog. */
	static boolean openListing(Diffs model) {
		try {
			var window = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window == null || window.getActivePage() == null) return false;
			var view = window.getActivePage().showView(ChangedFilesView.ID, null, org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
			if (view instanceof ChangedFilesView changes) changes.bind(model);
			return true;
		} catch (Exception ignored) { return false; }
	}

	/** Opens a transient file selector and then the existing compare editor. */
	static boolean openListingPopup(Diffs model) {
		try {
			var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window == null || window.getShell() == null || window.getShell().isDisposed()) return false;
			List<String> paths = model.currentFiles();
			if (paths.isEmpty()) return false;
			String[] labels = paths.stream()
					.map(path -> Path.of(path).getFileName().toString() + "\t" + path)
					.toArray(String[]::new);
			ElementListSelectionDialog dialog = new ElementListSelectionDialog(window.getShell(), new LabelProvider());
			dialog.setTitle("Current session changes");
			dialog.setMessage("Select a file to open Eclipse Compare:");
			dialog.setElements(labels);
			if (dialog.open() == Window.OK) {
				int index = java.util.Arrays.asList(labels).indexOf(dialog.getFirstResult());
				if (index >= 0) model.openCompare(paths.get(index));
			}
			return true;
		} catch (Exception ignored) { return false; }
	}

	/** Record the current on-disk content as the "before" if not already captured. */
	void snapshotIfAbsent(String absolutePath) {
		snapshots.computeIfAbsent(absolutePath, path -> gitBefore(path));
	}

	boolean changed(String path) {
		synchronized (authoritative) {
			ServerDiff diff = authoritative.get(path);
			if (diff != null) {
				// A patch-only diff (server omitted full before/after content, e.g. for
				// newly created files) still represents a real change.
				if (diff.before.isEmpty() && diff.after.isEmpty() && !diff.patch.isEmpty()) return true;
				return !diff.before.equals(diff.after);
			}
		}
		return !snapshots.getOrDefault(path, gitBefore(path)).equals(readOrEmpty(path));
	}

	void undo(String absolutePath) throws java.io.IOException {
		ServerDiff server = authoritative(absolutePath);
		String before = server == null ? snapshots.getOrDefault(absolutePath, gitBefore(absolutePath)) : server.before;
		Path path = Path.of(absolutePath);
		if (before.isEmpty() && (server != null || !tracked(path))) Files.deleteIfExists(path);
		else { Files.createDirectories(path.getParent()); Files.writeString(path, before, StandardCharsets.UTF_8); }
	}

	/** Open a compare editor: snapshot (before) vs current file (after). Must run on UI thread. */
	void openCompare(String absolutePath) {
		ServerDiff server = authoritative(absolutePath);
		String before = server == null ? snapshots.getOrDefault(absolutePath, "") : server.before;
		String after = server == null ? readOrEmpty(absolutePath) : server.after;
		boolean patchOnly = server != null && before.isEmpty() && after.isEmpty() && !server.patch.isEmpty();
		if (!patchOnly && before.equals(after)) {
			return; // nothing changed
		}
		if (reviewer != null) { reviewer.accept(absolutePath); return; }
		String name = Path.of(absolutePath).getFileName().toString();
		CompareConfiguration cfg = new CompareConfiguration();
		cfg.setLeftLabel(patchOnly ? name + " (no snapshot)" : name + " (before opencode)");
		cfg.setRightLabel(patchOnly ? name + " (unified patch)" : name + " (after)");
		cfg.setLeftEditable(false);
		cfg.setRightEditable(false);
		CompareEditorInput input = new TextCompareInput(cfg, name,
				new StringElement(name, before), new StringElement(name, patchOnly ? server.patch : after));
		CompareUI.openCompareEditor(input);
		if (Boolean.getBoolean("opencode.wholeViewProbe")) {
			System.out.println("[OpenCodeProbe] PASS compare opened " + name);
		}
	}

	private ServerDiff authoritative(String path) {
		synchronized (authoritative) { return authoritative.get(path); }
	}

	private static String string(com.google.gson.JsonObject object, String name) {
		return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : null;
	}

	private static String content(com.google.gson.JsonObject object, String... names) {
		for (String name : names) {
			String value = string(object, name);
			if (value != null) return value;
		}
		return "";
	}

	private record ServerDiff(String before, String after, String patch) { }

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
