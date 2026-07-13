package com.opencode.eclipse.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.ide.FileStoreEditorInput;

/** Opens the platform/global OpenCode config, creating it if needed. */
public final class OpenSettingsHandler extends AbstractHandler {
	static final String EDITOR_ID = "org.eclipse.ui.DefaultTextEditor";
	@Override public Object execute(ExecutionEvent event) throws ExecutionException {
		open();
		return null;
	}

	static void open() throws ExecutionException {
		try {
			Path config = configPath();
			Files.createDirectories(config.getParent());
			if (!Files.exists(config)) Files.writeString(config, "{}\n");
			var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			var store = EFS.getLocalFileSystem().fromLocalFile(config.toFile());
			IDE.openEditor(page, new FileStoreEditorInput(store), EDITOR_ID, true);
		} catch (Exception e) { throw new ExecutionException("Failed to open OpenCode settings", e); }
	}

	static Path configPath() {
		String override = System.getenv("OPENCODE_CONFIG");
		if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			return Path.of(appData != null ? appData : System.getProperty("user.home"), "opencode", "opencode.json");
		}
		if (os.contains("mac")) return Path.of(System.getProperty("user.home"), "Library", "Application Support",
				"opencode", "opencode.json");
		String xdg = System.getenv("XDG_CONFIG_HOME");
		Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
		return config.resolve("opencode").resolve("opencode.json");
	}
}
