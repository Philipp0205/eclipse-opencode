package com.opencode.eclipse.ui;

public final class WorkspaceRootTest {
	public static void main(String[] args) {
		assert "/scm/root".equals(WorkspaceRoot.resolve("/scm/root", "/eclipse/workspace"));
		assert "/eclipse/workspace".equals(WorkspaceRoot.resolve(null, "/eclipse/workspace"));
		assert "/eclipse/workspace".equals(WorkspaceRoot.resolve("  ", "/eclipse/workspace"));
		System.out.println("WORKSPACE ROOT OK");
	}
}
