package com.opencode.eclipse.ui;

import com.google.gson.JsonArray;

public final class PermissionDecisionsTest {
	public static void main(String[] args) {
		JsonArray patterns = new JsonArray();
		patterns.add("git push*");
		String key = PermissionDecisions.key("/tmp/project", "bash", patterns);
		assert key.equals(PermissionDecisions.key("/tmp/project", "bash", patterns))
				: "same request must map to the same key across processes";
		assert !key.equals(PermissionDecisions.key("/tmp/other", "bash", patterns));
		assert !key.equals(PermissionDecisions.key("/tmp/project", "edit", patterns));
		assert !key.equals(PermissionDecisions.key("/tmp/project", "bash", new JsonArray()));
		assert PermissionDecisions.key(null, null, null) != null : "an incomplete request still needs a key";

		FakePreferences node = new FakePreferences();
		PermissionDecisions decisions = new PermissionDecisions(node);
		assert decisions.remembered(key) == null;

		decisions.remember(key, PermissionDecisions.ALWAYS);
		assert PermissionDecisions.ALWAYS.equals(decisions.remembered(key));
		assert node.flushed : "remember() must flush so the answer survives an unclean shutdown";

		// A fresh instance over the same node is what a restarted Eclipse sees.
		assert PermissionDecisions.ALWAYS.equals(new PermissionDecisions(node).remembered(key));

		String denied = PermissionDecisions.key("/tmp/project", "webfetch", null);
		decisions.remember(denied, PermissionDecisions.REJECT);
		assert PermissionDecisions.REJECT.equals(decisions.remembered(denied));

		node.put("chatFontSize", "13");
		assert decisions.forgetAll() == 2;
		assert decisions.remembered(key) == null && decisions.remembered(denied) == null;
		assert "13".equals(node.get("chatFontSize", null)) : "unrelated preferences must survive";
		System.out.println("PERMISSION DECISIONS OK");
	}
}
