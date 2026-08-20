package com.opencode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.jface.dialogs.MessageDialog;

public final class SessionMonitorView extends ViewPart {
	public static final String ID = "com.opencode.eclipse.ui.sessionMonitorView";
	private Table table;
	private final Runnable refresh = this::refresh;
	private static final java.util.List<SessionMonitorView> INSTANCES = new java.util.concurrent.CopyOnWriteArrayList<>();
	static void refreshAll() { INSTANCES.forEach(view -> view.refresh()); }

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
			}
		});
		Menu menu = new Menu(table); table.setMenu(menu);
		MenuItem rename = new MenuItem(menu, SWT.PUSH); rename.setText("Rename…");
		rename.addListener(SWT.Selection, event -> {
			TableItem item = table.getSelectionCount() == 1 ? table.getSelection()[0] : null;
			if (item != null && item.getData() instanceof ChatViewRegistry.Entry entry) entry.view().renameFromMonitor();
		});
		MenuItem close = new MenuItem(menu, SWT.PUSH); close.setText("Close");
		close.addListener(SWT.Selection, event -> {
			TableItem item = table.getSelectionCount() == 1 ? table.getSelection()[0] : null;
			if (item != null && item.getData() instanceof ChatViewRegistry.Entry entry) entry.view().closeFromMonitor();
		});
		MenuItem delete = new MenuItem(menu, SWT.PUSH); delete.setText("Delete…");
		delete.addListener(SWT.Selection, event -> {
			TableItem item = table.getSelectionCount() == 1 ? table.getSelection()[0] : null;
			if (item == null || !(item.getData() instanceof ChatViewRegistry.Entry entry)) return;
			if (MessageDialog.openConfirm(getSite().getShell(), "Delete session", "Delete '" + entry.title() + "'?")) {
				entry.view().deleteFromMonitor();
			}
		});
		menu.addListener(SWT.Show, event -> {
			boolean selected = table.getSelectionCount() == 1
					&& table.getSelection()[0].getData() instanceof ChatViewRegistry.Entry;
			rename.setEnabled(selected); close.setEnabled(selected); delete.setEnabled(selected);
		});
		ChatViewRegistry.addListener(refresh); INSTANCES.add(this); refresh();
	}

	private void refresh() {
		if (table == null || table.isDisposed()) return;
		// Preserve the current selection across the rebuild so an in-progress
		// interaction (e.g. right-click rename) is not visibly disrupted by the
		// frequent updates that happen while subagents are active.
		Object selectedKey = table.getSelectionCount() == 1 ? rowKey(table.getSelection()[0].getData()) : null;
		table.removeAll();
		TableItem toReselect = null;
		for (ChatViewRegistry.Entry entry : ChatViewRegistry.snapshot()) {
			TableItem item = new TableItem(table, SWT.NONE);
			String rootAgent = entry.view().selectedAgentName();
			String rootAgentLabel = rootAgent == null ? "" : ChatView.displayName(rootAgent, AgentDescriptions.get(rootAgent));
			item.setText(new String[] { entry.title(), rootAgentLabel, entry.status().name() });
			item.setForeground(statusColor(entry.status()));
			item.setData(entry);
			if (entry.view().equals(selectedKey)) toReselect = item;
			for (ChildSessionTracker.Info child : ChildSessionTracker.infoFor(entry.view())) {
				TableItem childItem = new TableItem(table, SWT.NONE);
				String agentLabel = ChatView.displayName(child.agent(), AgentDescriptions.get(child.agent()));
				childItem.setText(new String[] { "↳ " + child.title(), agentLabel, child.status() });
				childItem.setForeground(childStatusColor(child.status()));
				childItem.setData(child);
				if (child.id().equals(selectedKey)) toReselect = childItem;
			}
		}
		if (toReselect != null) table.setSelection(toReselect);
		// Subagents that finished are removed from ChatView's tracking as soon as they
		// reach a terminal status, so there is nothing left here to filter out.
	}

	private static Object rowKey(Object data) {
		if (data instanceof ChatViewRegistry.Entry entry) return entry.view();
		if (data instanceof ChildSessionTracker.Info child) return child.id();
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
	@Override public void dispose() { INSTANCES.remove(this); ChatViewRegistry.removeListener(refresh); super.dispose(); }
}
