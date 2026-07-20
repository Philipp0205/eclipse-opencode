package com.opencode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.jface.dialogs.MessageDialog;

public final class SessionMonitorView extends ViewPart {
	public static final String ID = "com.opencode.eclipse.ui.sessionMonitorView";
	private Table table;
	private final Runnable refresh = this::refresh;

	@Override public void createPartControl(Composite parent) {
		table = new Table(parent, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
		table.setHeaderVisible(true); table.setLinesVisible(true);
		TableColumn name = new TableColumn(table, SWT.LEFT); name.setText("Session"); name.setWidth(260);
		TableColumn status = new TableColumn(table, SWT.LEFT); status.setText("Status"); status.setWidth(90);
		table.addListener(SWT.Selection, event -> {
			if (event.item instanceof TableItem item && item.getData() instanceof ChatViewRegistry.Entry entry)
				entry.view().getSite().getPage().activate(entry.view());
		});
		Menu menu = new Menu(table); table.setMenu(menu);
		MenuItem rename = new MenuItem(menu, SWT.PUSH); rename.setText("Rename…");
		rename.addListener(SWT.Selection, event -> {
			TableItem item = table.getSelectionCount() == 1 ? table.getSelection()[0] : null;
			if (item != null && item.getData() instanceof ChatViewRegistry.Entry entry) entry.view().renameFromMonitor();
		});
		MenuItem delete = new MenuItem(menu, SWT.PUSH); delete.setText("Close / delete session…");
		delete.addListener(SWT.Selection, event -> {
			TableItem item = table.getSelectionCount() == 1 ? table.getSelection()[0] : null;
			if (item == null || !(item.getData() instanceof ChatViewRegistry.Entry entry)) return;
			if (MessageDialog.openConfirm(getSite().getShell(), "Delete session", "Delete '" + entry.title() + "'?")) {
				entry.view().deleteFromMonitor();
			}
		});
		menu.addListener(SWT.Show, event -> { boolean selected = table.getSelectionCount() == 1; rename.setEnabled(selected); delete.setEnabled(selected); });
		ChatViewRegistry.addListener(refresh); refresh();
	}

	private void refresh() {
		if (table == null || table.isDisposed()) return;
		table.removeAll();
		for (ChatViewRegistry.Entry entry : ChatViewRegistry.snapshot()) {
			TableItem item = new TableItem(table, SWT.NONE); item.setText(new String[] { entry.title(), entry.status().name() });
			item.setData(entry);
		}
	}

	@Override public void setFocus() { if (table != null) table.setFocus(); }
	@Override public void dispose() { ChatViewRegistry.removeListener(refresh); super.dispose(); }
}
