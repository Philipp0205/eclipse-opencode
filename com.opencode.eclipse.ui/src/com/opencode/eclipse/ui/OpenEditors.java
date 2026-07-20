package com.opencode.eclipse.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.core.resources.IMarker;

/**
 * Reads the set of files the user has open in the editor area — the active tab
 * first, then the rest — as absolute filesystem paths. Used to attach workspace
 * context to prompts (opencode reads the files itself via its own tools).
 */
final class OpenEditors {

	private OpenEditors() {
	}

	record PromptTarget(java.util.function.Consumer<String> submit) { void submit(String text) { submit.accept(text); } }

	record Attached(String path, boolean active, String selection, String unsavedContent, List<String> problems) {
	}

	/** Active editor's file path, or null. */
	static String activePath() {
		IEditorPart active = activeEditor();
		return active != null ? filePath(active) : null;
	}

	/** All open editor files, active one flagged, de-duplicated, active first. */
	static List<Attached> all() {
		List<Attached> out = new ArrayList<>();
		IWorkbenchPage page = activePage();
		if (page == null) {
			return out;
		}
		String active = activePath();
		Set<String> seen = new LinkedHashSet<>();
		if (active != null && seen.add(active)) {
			out.add(attached(activeEditor(), active, true));
		}
		for (IEditorReference ref : page.getEditorReferences()) {
			IEditorPart part = ref.getEditor(false);
			String p = part != null ? filePath(part) : null;
			if (p != null && seen.add(p)) {
				out.add(attached(part, p, false));
			}
		}
		return out;
	}

	private static Attached attached(IEditorPart editor, String path, boolean active) {
		String selection = null, unsaved = null;
		if (editor instanceof ITextEditor textEditor) {
			var selected = textEditor.getSelectionProvider().getSelection();
			if (selected instanceof ITextSelection text && !text.isEmpty()) selection = text.getText();
			if (editor.isDirty()) {
				var document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
				if (document != null) unsaved = document.get();
			}
		}
		List<String> problems = new ArrayList<>();
		IFile file = editor != null ? editor.getEditorInput().getAdapter(IFile.class) : null;
		if (file != null) {
			try {
				for (IMarker marker : file.findMarkers(IMarker.PROBLEM, true, IFile.DEPTH_ZERO)) {
					String severity = switch (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)) {
						case IMarker.SEVERITY_ERROR -> "error"; case IMarker.SEVERITY_WARNING -> "warning"; default -> "info";
					};
					problems.add(severity + " line " + marker.getAttribute(IMarker.LINE_NUMBER, -1) + ": "
							+ marker.getAttribute(IMarker.MESSAGE, ""));
				}
			} catch (org.eclipse.core.runtime.CoreException ignored) { }
		}
		return new Attached(path, active, selection, unsaved, List.copyOf(problems));
	}

	private static IWorkbenchPage activePage() {
		IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		return w != null ? w.getActivePage() : null;
	}

	private static IEditorPart activeEditor() {
		IWorkbenchPage page = activePage();
		return page != null ? page.getActiveEditor() : null;
	}

	private static String filePath(IEditorPart editor) {
		IFile file = editor.getEditorInput().getAdapter(IFile.class);
		if (file != null && file.getLocation() != null) {
			return file.getLocation().toOSString();
		}
		if (editor.getEditorInput() instanceof FileStoreEditorInput external
				&& external.getURI() != null && "file".equalsIgnoreCase(external.getURI().getScheme())) {
			try { return java.nio.file.Path.of(external.getURI()).toString(); } catch (Exception ignored) { }
		}
		return null;
	}
}
