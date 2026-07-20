package com.opencode.eclipse.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class ChatViewRegistry {
	enum Status { running, blocked, done }
	record Entry(ChatView view, String title, Status status) { }
	private static final LinkedHashMap<ChatView, Entry> ENTRIES = new LinkedHashMap<>();
	private static final List<Runnable> LISTENERS = new ArrayList<>();
	private static ChatView active;

	private ChatViewRegistry() { }
	static void update(ChatView view, String title, Status status) {
		int before = ENTRIES.size(); Entry next = new Entry(view, title, status); Entry old = ENTRIES.put(view, next);
		if (!next.equals(old)) notifyListeners();
		if (before == 1 && ENTRIES.size() == 2) org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
			try { org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(SessionMonitorView.ID, null, org.eclipse.ui.IWorkbenchPage.VIEW_CREATE); } catch (Exception ignored) { }
		});
	}
	static void remove(ChatView view) { if (ENTRIES.remove(view) != null) notifyListeners(); }
	static void active(ChatView view) { if (active != view) { active = view; notifyListeners(); } }
	static ChatView active() { return active != null && ENTRIES.containsKey(active) ? active : ENTRIES.keySet().stream().findFirst().orElse(null); }
	static List<Entry> snapshot() { return List.copyOf(ENTRIES.values()); }
	static void addListener(Runnable listener) { LISTENERS.add(listener); }
	static void removeListener(Runnable listener) { LISTENERS.remove(listener); }
	static Status status(boolean busy, int blockers) { return blockers > 0 ? Status.blocked : busy ? Status.running : Status.done; }
	private static void notifyListeners() { List.copyOf(LISTENERS).forEach(Runnable::run); }
}
