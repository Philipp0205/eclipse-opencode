package com.opencode.eclipse.ui;

import java.util.List;

import com.opencode.eclipse.core.CommandInfo;

public final class CommandRouterMergeTest {
	public static void main(String[] args) {
		CommandInfo serverRename = new CommandInfo("rename", "Server-provided rename", "server", null, null, false, List.of());
		CommandInfo serverCustom = new CommandInfo("mytask", "A project command", "server", null, null, false, List.of());
		List<CommandInfo> merged = CommandRouter.mergedCommands(List.of(serverRename, serverCustom));

		// Client-owned commands must win over a server command with the same name.
		CommandInfo rename = merged.stream().filter(c -> c.name().equals("rename")).findFirst().orElseThrow();
		assert "client".equals(rename.source());

		// Server-only commands pass through untouched.
		assert merged.stream().anyMatch(c -> c.name().equals("mytask") && "server".equals(c.source()));

		// The connect command is always present even if the server doesn't provide it.
		assert merged.stream().anyMatch(c -> c.name().equals("connect"));

		// No duplicate names.
		assert merged.size() == merged.stream().map(CommandInfo::name).distinct().count();

		System.out.println("COMMAND ROUTER MERGE OK");
	}
}
