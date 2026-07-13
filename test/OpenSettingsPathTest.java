package com.opencode.eclipse.ui;

public final class OpenSettingsPathTest {
	public static void main(String[] args) {
		var path = OpenSettingsHandler.configPath();
		assert path.endsWith(java.nio.file.Path.of("opencode", "opencode.json")) : path;
		String xdg = System.getenv("XDG_CONFIG_HOME");
		if (xdg != null && !xdg.isBlank() && (System.getenv("OPENCODE_CONFIG") == null
				|| System.getenv("OPENCODE_CONFIG").isBlank())) assert path.startsWith(xdg) : path;
		assert OpenSettingsHandler.EDITOR_ID.equals("org.eclipse.ui.DefaultTextEditor");
		System.out.println("SETTINGS PATH OK " + path);
	}
}
