package com.opencode.eclipse.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.opencode.eclipse.core.OpenCodeService;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.part.ViewPart;

/** Shows both currently-live (in-process) sessions and, below them, past/closed sessions known
 * to the local OpenCode server (via {@link SessionHistory}), so this view remains useful after a
 * ChatView is closed or after an Eclipse restart. History rows have no open ChatView backing them;
 * see {@link HistoryRow}. */
public final class SessionMonitorView extends ViewPart {
	public static final String ID = "com.opencode.eclipse.ui.sessionMonitorView";
	private Table table;
	private final Runnable refresh = this::refresh;
	private static final java.util.List<SessionMonitorView> INSTANCES = new java.util.concurrent.CopyOnWriteArrayList<>();
	static void refreshAll() { INSTANCES.forEach(view -> view.refresh()); }

	/** Row data for a past/closed session with no currently-open ChatView. Rename/Delete act
	 * directly on the OpenCode server via {@link #historyService}, scoped by {@code directory}. */
	private record HistoryRow(String id, String title, String directory, String agent, boolean child) { }

	private volatile OpenCodeService historyService;
	private volatile boolean disposed;
	private String lastHistorySignature;
	private static final int POLL_INTERVAL_MS = 5_000;

	/** Test/probe support: true if any open OpenCode Sessions view currently shows at least
	 * one nested "↳ ..." child (subagent) row. */
	static boolean anyChildRowVisible() {
		for (SessionMonitorView view : INSTANCES) {
			if (view.table == null || view.table.isDisposed()) continue;
			for (TableItem item : view.table.getItems()) {
				if (item.getData() instanceof ChildSessionTracker.Info) return true;
			}
		}
		return false;
	}

	@Override public void createPartControl(Composite parent) {
		table = new Table(parent, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
		table.setHeaderVisible(true); table.setLinesVisible(true);
		TableColumn name = new TableColumn(table, SWT.LEFT); name.setText("Session"); name.setWidth(260);
		TableColumn agent = new TableColumn(table, SWT.LEFT); agent.setText("Agent"); agent.setWidth(110);
		TableColumn status = new TableColumn(table, SWT.LEFT); status.setText("Status"); status.setWidth(90);
		TableColumn type = new TableColumn(table, SWT.LEFT); type.setText("Type"); type.setWidth(70);
		table.addListener(SWT.MouseDoubleClick, event -> {
			TableItem[] selection = table.getSelection();
			if (selection.length != 1) return;
			Object data = selection[0].getData();
			if (data instanceof ChatViewRegistry.Entry entry) {
				entry.view().getSite().getPage().activate(entry.view());
			} else if (data instanceof ChildSessionTracker.Info child && child.directory() != null) {
				ChatViewRegistry.snapshot().stream().map(ChatViewRegistry.Entry::view)
						.filter(view -> ChildSessionTracker.infoFor(view).contains(child)).findFirst()
						.ifPresent(owner -> SubagentSessionPopup.open(owner, child.id(), child.directory(), child.title()));
			} else if (data instanceof HistoryRow history) {
				ChatView.openFromExplorer(history.directory(), history.id());
			}
		});
		Menu menu = new Menu(table); table.setMenu(menu);
		MenuItem rename = new MenuItem(menu, SWT.PUSH); rename.setText("Rename…");
		rename.addListener(SWT.Selection, event -> {
			TableItem item = table.getSelectionCount() == 1 ? table.getSelection()[0] : null;
			if (item == null) return;
			if (item.getData() instanceof ChatViewRegistry.Entry entry) entry.view().renameFromMonitor();
			else if (item.getData() instanceof HistoryRow history) renameHistoryRow(history);
		});
		MenuItem close = new MenuItem(menu, SWT.PUSH); close.setText("Close");
		close.addListener(SWT.Selection, event -> {
			TableItem item = table.getSelectionCount() == 1 ? table.getSelection()[0] : null;
			if (item != null && item.getData() instanceof ChatViewRegistry.Entry entry) entry.view().closeFromMonitor();
		});
		MenuItem delete = new MenuItem(menu, SWT.PUSH); delete.setText("Delete…");
		delete.addListener(SWT.Selection, event -> {
			TableItem item = table.getSelectionCount() == 1 ? table.getSelection()[0] : null;
			if (item == null) return;
			if (item.getData() instanceof ChatViewRegistry.Entry entry) {
				if (MessageDialog.openConfirm(getSite().getShell(), "Delete session", "Delete '" + entry.title() + "'?")) {
					entry.view().deleteFromMonitor();
				}
			} else if (item.getData() instanceof HistoryRow history) {
				deleteHistoryRow(history);
			}
		});
		menu.addListener(SWT.Show, event -> {
			Object data = table.getSelectionCount() == 1 ? table.getSelection()[0].getData() : null;
			boolean liveRoot = data instanceof ChatViewRegistry.Entry;
			boolean history = data instanceof HistoryRow;
			rename.setEnabled(liveRoot || history);
			close.setEnabled(liveRoot);
			delete.setEnabled(liveRoot || history);
		});
		ChatViewRegistry.addListener(refresh); INSTANCES.add(this); refresh();
		schedulePoll();
	}

	private void renameHistoryRow(HistoryRow history) {
		InputDialog dialog = new InputDialog(getSite().getShell(), "Rename session", "New title:", history.title(), null);
		if (dialog.open() != InputDialog.OK) return;
		String title = dialog.getValue();
		if (title == null || title.isBlank()) return;
		new Thread(() -> {
			try {
				historyService().renameSession(history.directory(), history.id(), title);
			} catch (Exception ignored) {
				// best-effort: next poll will simply show the previous title if the rename failed
			}
			refreshHistoryAsync();
		}, "opencode-session-monitor-rename").start();
	}

	private void deleteHistoryRow(HistoryRow history) {
		if (!MessageDialog.openConfirm(getSite().getShell(), "Delete session", "Delete '" + history.title() + "'?")) return;
		new Thread(() -> {
			try {
				historyService().deleteSession(history.directory(), history.id());
			} catch (Exception ignored) {
				// best-effort: next poll will simply still show the row if the delete failed
			}
			refreshHistoryAsync();
		}, "opencode-session-monitor-delete").start();
	}

	private OpenCodeService historyService() throws java.io.IOException {
		OpenCodeService service = historyService;
		if (service == null) {
			synchronized (this) {
				service = historyService;
				if (service == null) {
					service = new OpenCodeService();
					service.initialize(SessionHistory.effectiveWorkspaceRoot());
					historyService = service;
				}
			}
		}
		return service;
	}

	private void schedulePoll() {
		if (table == null || table.isDisposed() || disposed) return;
		table.getDisplay().timerExec(POLL_INTERVAL_MS, () -> {
			if (disposed || table == null || table.isDisposed()) return;
			refreshHistoryAsync();
			schedulePoll();
		});
	}

	private void refreshHistoryAsync() {
		if (table == null || table.isDisposed() || disposed) return;
		new Thread(() -> {
			try {
				OpenCodeService service = historyService();
				SessionHistory.FetchResult result = SessionHistory.fetchAll(service);
				List<JsonObject> sessions = new ArrayList<>(result.sessions().values());
				ui(() -> applyHistory(sessions));
			} catch (Exception ignored) {
				// quiet background poll: leave whatever history rows are currently shown
			}
		}, "opencode-session-monitor-history").start();
	}

	private List<HistoryRow> historyRows = List.of();

	private void applyHistory(List<JsonObject> sessions) {
		String signature = historySignature(sessions);
		if (signature.equals(lastHistorySignature)) return;
		lastHistorySignature = signature;
		List<HistoryRow> rows = new ArrayList<>();
		for (JsonObject session : sessions) {
			String id = SessionHistory.value(session, "id");
			if (id.isBlank()) continue;
			String title = SessionHistory.value(session, "title");
			if (title.isBlank()) title = "New Session";
			rows.add(new HistoryRow(id, title, SessionHistory.value(session, "directory"),
					SessionHistory.value(session, "agent"), !SessionHistory.value(session, "parentID").isBlank()));
		}
		historyRows = rows;
		refresh();
	}

	private static String historySignature(List<JsonObject> sessions) {
		StringBuilder sb = new StringBuilder();
		for (JsonObject session : sessions) {
			sb.append(SessionHistory.value(session, "id")).append('\u0001')
					.append(SessionHistory.value(session, "title")).append('\u0001')
					.append(SessionHistory.value(session, "directory")).append('\u0002');
		}
		return sb.toString();
	}

	private void ui(Runnable run) {
		if (!disposed && table != null && !table.isDisposed()) {
			table.getDisplay().asyncExec(() -> { if (!disposed && !table.isDisposed()) run.run(); });
		}
	}

	private void refresh() {
		if (table == null || table.isDisposed()) return;
		// Preserve the current selection across the rebuild so an in-progress
		// interaction (e.g. right-click rename) is not visibly disrupted by the
		// frequent updates that happen while subagents are active.
		Object selectedKey = table.getSelectionCount() == 1 ? rowKey(table.getSelection()[0].getData()) : null;
		table.removeAll();
		TableItem toReselect = null;
		java.util.Set<String> liveIds = new java.util.HashSet<>();
		for (ChatViewRegistry.Entry entry : ChatViewRegistry.snapshot()) {
			TableItem item = new TableItem(table, SWT.NONE);
			String rootAgent = entry.view().selectedAgentName();
			String rootAgentLabel = rootAgent == null ? "" : ChatView.displayName(rootAgent, AgentDescriptions.get(rootAgent));
			item.setText(new String[] { entry.title(), rootAgentLabel, entry.status().name(), "Live" });
			item.setForeground(statusColor(entry.status()));
			item.setData(entry);
			if (entry.view().equals(selectedKey)) toReselect = item;
			String liveId = entry.view().service.getCurrentSessionId();
			if (liveId != null) liveIds.add(liveId);
			for (ChildSessionTracker.Info child : ChildSessionTracker.infoFor(entry.view())) {
				TableItem childItem = new TableItem(table, SWT.NONE);
				String agentLabel = ChatView.displayName(child.agent(), AgentDescriptions.get(child.agent()));
				childItem.setText(new String[] { "↳ " + child.title(), agentLabel, child.status(), "Live" });
				childItem.setForeground(childStatusColor(child.status()));
				childItem.setData(child);
				if (child.id().equals(selectedKey)) toReselect = childItem;
				liveIds.add(child.id());
			}
		}
		for (HistoryRow history : historyRows) {
			if (liveIds.contains(history.id())) continue; // already shown as a live row above
			TableItem item = new TableItem(table, SWT.NONE);
			String agentLabel = history.agent().isBlank() ? "" : ChatView.displayName(history.agent(), AgentDescriptions.get(history.agent()));
			String prefix = history.child() ? "↳ " : "";
			item.setText(new String[] { prefix + history.title(), agentLabel, "", "History" });
			item.setForeground(Display.getCurrent().getSystemColor(SWT.COLOR_DARK_GRAY));
			item.setData(history);
			if (history.id().equals(selectedKey)) toReselect = item;
		}
		if (toReselect != null) table.setSelection(toReselect);
		// Subagents that finished are removed from ChatView's tracking as soon as they
		// reach a terminal status, so there is nothing left here to filter out.
	}

	private static Object rowKey(Object data) {
		if (data instanceof ChatViewRegistry.Entry entry) return entry.view();
		if (data instanceof ChildSessionTracker.Info child) return child.id();
		if (data instanceof HistoryRow history) return history.id();
		return null;
	}

	private static Color statusColor(ChatViewRegistry.Status status) {
		return switch (status) {
		case blocked -> Display.getCurrent().getSystemColor(SWT.COLOR_DARK_YELLOW);
		case done -> Display.getCurrent().getSystemColor(SWT.COLOR_DARK_GREEN);
		default -> Display.getCurrent().getSystemColor(SWT.COLOR_LIST_FOREGROUND);
		};
	}

	private static Color childStatusColor(String status) {
		int code = switch (status) {
		case "blocked" -> SWT.COLOR_DARK_YELLOW;
		case "error", "cancelled" -> SWT.COLOR_DARK_RED;
		default -> SWT.COLOR_LIST_FOREGROUND;
		};
		return Display.getCurrent().getSystemColor(code);
	}

	@Override public void setFocus() { if (table != null) table.setFocus(); }
	@Override public void dispose() {
		disposed = true;
		INSTANCES.remove(this);
		ChatViewRegistry.removeListener(refresh);
		OpenCodeService service = historyService;
		if (service != null) new Thread(service::dispose, "opencode-session-monitor-cleanup").start();
		super.dispose();
	}
}
