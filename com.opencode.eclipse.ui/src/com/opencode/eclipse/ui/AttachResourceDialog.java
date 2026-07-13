package com.opencode.eclipse.ui;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.FilteredResourcesSelectionDialog;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PlatformUI;

/** Eclipse workspace file/folder search used by the paperclip button. */
final class AttachResourceDialog extends FilteredResourcesSelectionDialog {
	private static final String OPENED_FILES = "${OPENED_FILES}";
	private static final Set<String> BLOCKED = Set.of("class", "jar", "exe", "dll", "so", "bin", "pdf",
			"docx", "xlsx", "pptx", "mp3", "wav", "mp4", "avi", "mov", "iso", "dmg");

	AttachResourceDialog(Shell shell, IContainer root) {
		super(shell, true, root, IResource.FILE | IResource.FOLDER);
		setTitle("Attach workspace resources");
		setMessage("Choose files or folders, or type to search:");
		setInitialPattern(OPENED_FILES, NONE);
		refresh();
	}

	@Override protected ItemsFilter createFilter() {
		return new ResourceFilter() {
			private final Set<java.net.URI> seen = new HashSet<>();
			private final List<IFile> opened = openedFiles();

			@Override public boolean matchItem(Object item) {
				if (!(item instanceof IResource resource) || resource.getLocationURI() == null
						|| !seen.add(resource.getLocationURI())) return false;
				if (item instanceof IFile file) {
					String extension = file.getFileExtension();
					if (extension != null && BLOCKED.contains(extension.toLowerCase())) return false;
					return OPENED_FILES.equals(patternMatcher.getPattern()) ? opened.contains(file) : super.matchItem(item);
				}
				return item instanceof IFolder && super.matchItem(item);
			}
		};
	}

	private static List<IFile> openedFiles() {
		List<IFile> files = new ArrayList<>();
		var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		var page = window != null ? window.getActivePage() : null;
		if (page != null) for (var reference : page.getEditorReferences()) {
			try {
				var input = reference.getEditorInput();
				if (input instanceof IFileEditorInput fileInput) files.add(fileInput.getFile());
			} catch (org.eclipse.ui.PartInitException ignored) { }
		}
		return files;
	}
}
