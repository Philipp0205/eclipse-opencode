package com.opencode.eclipse.ui;

import java.util.List;
import com.opencode.eclipse.core.CommandInfo;

public final class SlashCommandsTest {
	public static void main(String[] args) {
		var review = new CommandInfo("review", "Review changes", "command", null, null, true,
				List.of("$ARGUMENTS"));
		var skill = new CommandInfo("customize-opencode", "Customize", "skill", null, null, false, List.of());
		var compact = new CommandInfo("compact", "Compact", "client", null, null, false, List.of());
		var connect = new CommandInfo("connect", "Connect", "client", null, null, false, List.of());
		List<CommandInfo> commands = List.of(review, skill, compact, connect);
		assert SlashCommands.filter(commands, "/rv").equals(List.of(review));
		assert SlashCommands.filter(commands, "/review branch").isEmpty();
		var invocation = SlashCommands.parse(commands, "/review branch name");
		assert invocation.command().equals(review) && invocation.arguments().equals("branch name");
		assert SlashCommands.parse(commands, "/unknown value") == null;
		assert SlashCommands.parse(commands, "/compact").command().equals(compact);
		assert SlashCommands.parse(commands, "/connect").command().equals(connect);
		System.out.println("SLASH COMMANDS OK");
	}
}
