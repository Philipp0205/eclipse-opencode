package com.opencode.eclipse.ui;

import java.util.List;

import com.opencode.eclipse.core.CommandInfo;

/** Parsing and filtering for server-discovered slash commands. */
final class SlashCommands {
	record Invocation(CommandInfo command, String arguments) { }

	private SlashCommands() { }

	static List<CommandInfo> filter(List<CommandInfo> commands, String input) {
		if (!input.startsWith("/") || input.indexOf(' ') >= 0 || input.indexOf('\n') >= 0) return List.of();
		String query = input.substring(1).toLowerCase();
		return commands.stream()
				.filter(command -> fuzzy(command.name().toLowerCase(), query))
				.sorted((a, b) -> Integer.compare(score(a.name().toLowerCase(), query), score(b.name().toLowerCase(), query)))
				.toList();
	}

	static Invocation parse(List<CommandInfo> commands, String input) {
		if (!input.startsWith("/")) return null;
		int split = input.indexOf(' ');
		String name = input.substring(1, split < 0 ? input.length() : split);
		String arguments = split < 0 ? "" : input.substring(split + 1);
		return commands.stream().filter(command -> command.name().equals(name)).findFirst()
				.map(command -> new Invocation(command, arguments)).orElse(null);
	}

	private static boolean fuzzy(String value, String query) {
		int i = 0;
		for (int j = 0; i < query.length() && j < value.length(); j++) if (value.charAt(j) == query.charAt(i)) i++;
		return i == query.length();
	}

	private static int score(String value, String query) {
		if (query.isEmpty()) return 0;
		int exact = value.indexOf(query);
		return exact >= 0 ? exact : value.length();
	}
}
