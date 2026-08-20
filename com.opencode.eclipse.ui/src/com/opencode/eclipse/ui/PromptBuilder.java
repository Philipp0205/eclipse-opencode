package com.opencode.eclipse.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import com.opencode.eclipse.core.FilePartInput;

/**
 * Pure prompt-text and attachment-payload construction, split out of {@link ChatView} so it
 * can be unit-tested without any SWT/Eclipse dependency.
 */
final class PromptBuilder {
	private PromptBuilder() { }

	/** Prefix the prompt with the attached file paths so the agent knows the working set. */
	static String withAttachedContext(String text, List<OpenEditors.Attached> attached) {
		if (attached.isEmpty()) {
			return text;
		}
		StringBuilder sb = new StringBuilder("Attached files (currently open in the editor):\n");
		for (OpenEditors.Attached a : attached) {
			sb.append("- ").append(a.path());
			if (a.active()) {
				sb.append("  (active tab)");
			}
			sb.append('\n');
			if (a.selection() != null) sb.append("  Selected text:\n```\n").append(a.selection()).append("\n```\n");
			if (a.unsavedContent() != null) sb.append("  Unsaved editor content:\n```\n")
					.append(a.unsavedContent()).append("\n```\n");
			for (String problem : a.problems()) sb.append("  Eclipse problem: ").append(problem).append('\n');
		}
		sb.append('\n').append(text);
		return sb.toString();
	}

	static List<FilePartInput> fileParts(List<OpenEditors.Attached> attachments) {
		return attachments.stream().map(attachment -> {
			Path path = Path.of(attachment.path());
			String mime;
			try {
				mime = Files.isDirectory(path) ? "application/x-directory" : Files.probeContentType(path);
			} catch (Exception ignored) {
				mime = null;
			}
			if (mime == null) mime = attachment.unsavedContent() != null ? "text/plain" : "application/octet-stream";
			String url = attachment.unsavedContent() == null ? path.toUri().toString()
					: "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(
							attachment.unsavedContent().getBytes(StandardCharsets.UTF_8));
			return new FilePartInput(mime, path.getFileName().toString(), url);
		}).toList();
	}
}
