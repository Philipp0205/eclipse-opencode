package com.opencode.eclipse.ui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide cache of agent name -&gt; description, so the leading emoji an agent's description
 * starts with (see {@link ChatView#leadingEmoji}) can be shown next to its name in views that
 * only have the bare agent name to work with (Sessions monitor, Sessions explorer), not the
 * live {@code GET /agent} response. Populated whenever any {@link ChatView} loads its agent
 * list; read by any view rendering an agent name.
 */
final class AgentDescriptions {
	private static final Map<String, String> DESCRIPTIONS = new ConcurrentHashMap<>();

	private AgentDescriptions() { }

	static void put(String name, String description) {
		if (name != null && description != null && !description.isBlank()) DESCRIPTIONS.put(name, description);
	}

	static String get(String name) {
		return name == null ? null : DESCRIPTIONS.get(name);
	}
}
