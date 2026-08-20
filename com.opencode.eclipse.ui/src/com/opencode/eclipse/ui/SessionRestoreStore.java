package com.opencode.eclipse.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;

/**
 * Persists and restores the last-used OpenCode session id/directory for one ChatView
 * (keyed by its secondary view id), so a session is restored across Eclipse restarts.
 *
 * <p>Preference writes are explicitly flushed to disk. Two earlier fixes to session
 * restore only updated the logic that <em>decides</em> what to restore; the actual bug was
 * that {@link IEclipsePreferences#put}/{@code remove} only guarantee an in-memory update —
 * a durable write requires an explicit {@link IEclipsePreferences#flush()} call, since the
 * platform's own shutdown-participant flush is not guaranteed on every exit path (crash,
 * force-quit, some "Restart" flows). Keep the flush on every write here.
 */
final class SessionRestoreStore {
	private final IEclipsePreferences node;
	private final String key;

	SessionRestoreStore(IEclipsePreferences node, String secondaryId) {
		this.node = node;
		this.key = preferenceKey(secondaryId);
	}

	static String preferenceKey(String secondaryId) {
		if (secondaryId == null || secondaryId.isBlank()) return "lastSession_primary";
		String encoded = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(secondaryId.getBytes(StandardCharsets.UTF_8));
		return "lastSession_" + encoded;
	}

	String loadSessionId() {
		return node.get(key, null);
	}

	String loadDirectory() {
		return node.get(key + "_dir", null);
	}

	void persist(String sessionId, String directory) {
		if (sessionId == null || sessionId.isBlank()) return;
		node.put(key, sessionId);
		if (directory != null && !directory.isBlank()) {
			node.put(key + "_dir", directory);
		} else {
			node.remove(key + "_dir");
		}
		flush();
	}

	void clear() {
		node.remove(key);
		node.remove(key + "_dir");
		flush();
	}

	private void flush() {
		try {
			node.flush();
		} catch (org.osgi.service.prefs.BackingStoreException ignored) {
			// Best-effort durability; the in-memory value is still correct for this run.
		}
	}
}
