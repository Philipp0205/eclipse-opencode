package com.opencode.eclipse.ui;

import java.util.List;

import com.opencode.eclipse.core.FilePartInput;

public final class PromptBuilderTest {
	public static void main(String[] args) {
		assert PromptBuilder.withAttachedContext("hi", List.of()).equals("hi");

		var attached = new OpenEditors.Attached("/tmp/a.txt", true, "sel text", null, List.of("warning: unused import"));
		String withContext = PromptBuilder.withAttachedContext("do the thing", List.of(attached));
		assert withContext.contains("/tmp/a.txt");
		assert withContext.contains("(active tab)");
		assert withContext.contains("sel text");
		assert withContext.contains("warning: unused import");
		assert withContext.endsWith("do the thing");

		List<FilePartInput> parts = PromptBuilder.fileParts(List.of(attached));
		assert parts.size() == 1;
		assert parts.get(0).filename().equals("a.txt");

		var unsaved = new OpenEditors.Attached("/tmp/b.txt", false, null, "unsaved content", List.of());
		List<FilePartInput> unsavedParts = PromptBuilder.fileParts(List.of(unsaved));
		assert unsavedParts.get(0).url().startsWith("data:text/plain;base64,");

		System.out.println("PROMPT BUILDER OK");
	}
}
