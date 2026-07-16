package com.opencode.eclipse.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class ChatViewRegistry {
	enum Status { running, blocked, done }
	record Entry(ChatView view, String title, Status status) { }
	private static final LinkedHashMap<ChatView, Entry> ENTRIES = new LinkedHashMap<>();
	private static final List<Runnable> LISTENERS = new ArrayList<>();

	private ChatViewRegistry() { }
	static void update(ChatView view, String title, Status status) { ENTRIES.put(view, new Entry(view, title, status)); notifyListeners(); }
	static void remove(ChatView view) { ENTRIES.remove(view); notifyListeners(); }
	static List<Entry> snapshot() { return List.copyOf(ENTRIES.values()); }
	static void addListener(Runnable listener) { LISTENERS.add(listener); }
	static void removeListener(Runnable listener) { LISTENERS.remove(listener); }
	static Status status(boolean busy, int blockers) { return blockers > 0 ? Status.blocked : busy ? Status.running : Status.done; }
	private static void notifyListeners() { List.copyOf(LISTENERS).forEach(Runnable::run); }
}
