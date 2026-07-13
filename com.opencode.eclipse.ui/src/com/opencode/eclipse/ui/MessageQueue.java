package com.opencode.eclipse.ui;

import java.util.ArrayDeque;
import java.util.List;

/** Small in-memory FIFO matching OpenCode TUI queued-prompt semantics. */
final class MessageQueue<T> {
	private final ArrayDeque<T> items = new ArrayDeque<>();
	void add(T item) { items.add(item); }
	T poll() { return items.poll(); }
	boolean remove(T item) { return items.remove(item); }
	boolean isEmpty() { return items.isEmpty(); }
	int size() { return items.size(); }
	List<T> snapshot() { return List.copyOf(items); }
}
