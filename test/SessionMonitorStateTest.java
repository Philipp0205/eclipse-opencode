package com.opencode.eclipse.ui;

public final class SessionMonitorStateTest {
	public static void main(String[] args) {
		assert ChatViewRegistry.status(false, 0) == ChatViewRegistry.Status.done;
		assert ChatViewRegistry.status(true, 0) == ChatViewRegistry.Status.running;
		assert ChatViewRegistry.status(false, 1) == ChatViewRegistry.Status.blocked;
		assert ChatViewRegistry.status(true, 1) == ChatViewRegistry.Status.blocked;
		assert "ABCD-1234".equals(ConnectProviderDialog.deviceCode("Enter code: ABCD-1234"));
		System.out.println("SESSION MONITOR STATE OK");
	}
}
