package com.opencode.eclipse.core;

import java.util.List;

/** Server-discovered slash command, MCP prompt, or skill. */
public record CommandInfo(String name, String description, String source, String agent,
		String model, boolean subtask, List<String> hints) {
}
