package com.opencode.eclipse.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import com.google.gson.JsonArray;

/**
 * Remembers the lasting answers ("always" / "never") to OpenCode permission prompts.
 *
 * <p>OpenCode's own "always" reply only holds for the lifetime of the {@code opencode serve}
 * process, and each ChatView starts its own. Keeping the answer on the Eclipse side is what
 * makes it survive a continued session, a view reopen, and an IDE restart. "once" is
 * deliberately never stored.
 *
 * <p>Writes are flushed for the same reason as {@link SessionRestoreStore}: {@code put} only
 * guarantees an in-memory update, and the platform's shutdown flush is not reached on every
 * exit path.
 */
final class PermissionDecisions {
	static final String ONCE = "once";
	static final String ALWAYS = "always";
	static final String REJECT = "reject";

	private static final String PREFIX = "permission_";

	private final IEclipsePreferences node;

	PermissionDecisions(IEclipsePreferences node) {
		this.node = node;
	}

	/**
	 * Stable identity of one permission request: the same action, on the same patterns, in the
	 * same directory must map to the same key across processes — so it is hashed from the request
	 * content rather than derived from the per-request id, which changes on every ask.
	 */
	static String key(String directory, String permission, JsonArray patterns) {
		return PREFIX + hash(String.join("\u0000", directory == null ? "" : directory,
				permission == null ? "" : permission, patterns == null ? "" : patterns.toString()));
	}

	/** {@link #ALWAYS}, {@link #REJECT}, or null when no lasting decision was made yet. */
	String remembered(String key) {
		return node.get(key, null);
	}

	void remember(String key, String reply) {
		node.put(key, reply);
		flush();
	}

	/** Drops every remembered answer, so the next request asks again. */
	int forgetAll() {
		int removed = 0;
		try {
			for (String key : node.keys()) {
				if (key.startsWith(PREFIX)) {
					node.remove(key);
					removed++;
				}
			}
		} catch (org.osgi.service.prefs.BackingStoreException ex) {
			return removed;
		}
		flush();
		return removed;
	}

	private void flush() {
		try {
			node.flush();
		} catch (org.osgi.service.prefs.BackingStoreException ignored) {
			// Best-effort durability; the in-memory value is still correct for this run.
		}
	}

	private static String hash(String identity) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 16));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every JRE", e);
		}
	}
}
