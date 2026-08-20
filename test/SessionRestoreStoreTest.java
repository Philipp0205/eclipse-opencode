package com.opencode.eclipse.ui;

public final class SessionRestoreStoreTest {
	public static void main(String[] args) throws Exception {
		assert SessionRestoreStore.preferenceKey(null).equals("lastSession_primary");
		assert SessionRestoreStore.preferenceKey("").equals("lastSession_primary");
		String encodedKey = SessionRestoreStore.preferenceKey("secondary-1");
		assert encodedKey.startsWith("lastSession_") && !encodedKey.equals("lastSession_primary");

		FakePreferences node = new FakePreferences();
		var store = new SessionRestoreStore(node, "secondary-1");
		assert store.loadSessionId() == null;
		assert store.loadDirectory() == null;

		store.persist("session-abc", "/tmp/project");
		assert "session-abc".equals(store.loadSessionId());
		assert "/tmp/project".equals(store.loadDirectory());
		assert node.flushed : "persist() must flush so the write survives an unclean shutdown";

		// A fresh store instance backed by the same node/secondary id sees the persisted values —
		// this is exactly what a restarted ChatView does on the next Eclipse launch.
		var reopened = new SessionRestoreStore(node, "secondary-1");
		assert "session-abc".equals(reopened.loadSessionId());
		assert "/tmp/project".equals(reopened.loadDirectory());

		store.persist("session-def", null);
		assert "session-def".equals(store.loadSessionId());
		assert store.loadDirectory() == null;

		node.flushed = false;
		store.clear();
		assert store.loadSessionId() == null;
		assert store.loadDirectory() == null;
		assert node.flushed : "clear() must flush too";

		store.persist("", "/tmp/project");
		assert store.loadSessionId() == null : "blank session id must not be persisted";

		System.out.println("SESSION RESTORE STORE OK");
	}
}
