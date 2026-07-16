package com.opencode.eclipse.ui;

final class WorkspaceRoot {
	private WorkspaceRoot() { }

	static String resolve(String scmRoot, String eclipseRoot) {
		return scmRoot != null && !scmRoot.isBlank() ? scmRoot : eclipseRoot;
	}
}
