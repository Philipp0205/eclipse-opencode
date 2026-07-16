package com.opencode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.part.ViewPart;

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
