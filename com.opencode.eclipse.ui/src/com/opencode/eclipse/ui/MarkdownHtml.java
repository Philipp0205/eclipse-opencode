package com.opencode.eclipse.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Small safe Markdown-to-HTML renderer for chat messages, including GFM tables. */
final class MarkdownHtml {
	private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*$");

	private MarkdownHtml() {
	}

	static String render(String markdown) {
		if (markdown == null || markdown.isEmpty()) return "";
		String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		StringBuilder out = new StringBuilder();
		boolean code = false, list = false;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.startsWith("```")) {
				if (list) { out.append("</ul>"); list = false; }
				out.append(code ? "</code></pre>" : "<pre><code>"); code = !code; continue;
			}
			if (code) { out.append(ConversationHtml.escape(line)).append('\n'); continue; }
			if (i + 1 < lines.length && line.contains("|") && TABLE_DIVIDER.matcher(lines[i + 1]).matches()) {
				if (list) { out.append("</ul>"); list = false; }
				List<String> headers = cells(line); out.append("<table><thead><tr>");
				headers.forEach(cell -> out.append("<th>").append(inline(cell)).append("</th>"));
				out.append("</tr></thead><tbody>"); i += 2;
				while (i < lines.length && lines[i].contains("|") && !lines[i].isBlank()) {
					out.append("<tr>"); cells(lines[i]).forEach(cell -> out.append("<td>").append(inline(cell)).append("</td>"));
					out.append("</tr>"); i++;
				}
				out.append("</tbody></table>"); i--; continue;
			}
			if (line.matches("^#{1,6}\\s+.*")) {
				if (list) { out.append("</ul>"); list = false; }
				int level = 0; while (line.charAt(level) == '#') level++;
				out.append("<h").append(level).append('>').append(inline(line.substring(level).trim()))
						.append("</h").append(level).append('>'); continue;
			}
			if (line.matches("^\\s*[-*]\\s+.*")) {
				if (!list) { out.append("<ul>"); list = true; }
				String item = line.replaceFirst("^\\s*[-*]\\s+", "");
				boolean checked = item.matches("^\\[[xX]\\]\\s+.*");
				boolean task = checked || item.matches("^\\[ \\]\\s+.*");
				if (task) item = item.substring(3).trim();
				out.append("<li>").append(task ? (checked ? "☑ " : "☐ ") : "").append(inline(item)).append("</li>"); continue;
			}
			if (list) { out.append("</ul>"); list = false; }
			if (line.isBlank()) continue;
			if (line.startsWith("> ")) out.append("<blockquote>").append(inline(line.substring(2))).append("</blockquote>");
			else out.append("<p>").append(inline(line)).append("</p>");
		}
		if (list) out.append("</ul>");
		if (code) out.append("</code></pre>");
		return out.toString();
	}

	private static List<String> cells(String line) {
		String value = line.trim();
		if (value.startsWith("|")) value = value.substring(1);
		if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
		List<String> cells = new ArrayList<>();
		for (String cell : value.split("\\|", -1)) cells.add(cell.trim());
		return cells;
	}

	private static String inline(String value) {
		String safe = ConversationHtml.escape(value);
		safe = safe.replaceAll("`([^`]+)`", "<code>$1</code>")
				.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>")
				.replaceAll("~~([^~]+)~~", "<del>$1</del>")
				.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
		return safe.replaceAll("\\[([^]]+)]\\((https?://[^ )]+)\\)", "<a href=\"$2\">$1</a>");
	}
}
