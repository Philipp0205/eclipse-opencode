package com.opencode.eclipse.ui;

public final class AgentDisplayNameTest {
	public static void main(String[] args) {
		assert ChatView.leadingEmoji(null).isEmpty();
		assert ChatView.leadingEmoji("").isEmpty();
		assert ChatView.leadingEmoji("Builds and edits code").isEmpty();
		assert ChatView.leadingEmoji("42 is the answer").isEmpty();
		assert ChatView.leadingEmoji("\uD83C\uDFAF Coordinates subagents").equals("\uD83C\uDFAF") : ChatView.leadingEmoji("\uD83C\uDFAF Coordinates subagents");
		assert ChatView.leadingEmoji("\u2705\uFE0F Reviews changes").equals("\u2705\uFE0F");

		assert ChatView.displayName("build", "Builds and edits code").equals("build");
		assert ChatView.displayName("orchestrator", "\uD83C\uDFAF Coordinates subagents").equals("\uD83C\uDFAF orchestrator");
		System.out.println("AGENT DISPLAY NAME OK");
	}
}
