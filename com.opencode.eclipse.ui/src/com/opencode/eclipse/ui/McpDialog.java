package com.opencode.eclipse.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.google.gson.JsonObject;
import com.opencode.eclipse.core.OpenCodeService;

/** Minimal MCP status and connect/disconnect management. */
final class McpDialog extends Dialog {
	private final OpenCodeService service;
	private Composite content;

	McpDialog(Shell parent, OpenCodeService service) { super(parent); this.service = service; }

	@Override protected void configureShell(Shell shell) { super.configureShell(shell); shell.setText("OpenCode MCP servers"); }
	@Override protected Control createDialogArea(Composite parent) {
		content = (Composite) super.createDialogArea(parent); content.setLayout(new GridLayout(3, false)); refresh(); return content;
	}

	private void refresh() {
		for (var child : content.getChildren()) child.dispose();
		try {
			JsonObject status = service.getMcpStatus();
			for (var entry : status.entrySet()) {
				String name = entry.getKey(); JsonObject value = entry.getValue().getAsJsonObject();
				new Label(content, SWT.NONE).setText(name);
				String state = Events.str(value, "status"); new Label(content, SWT.NONE).setText(state != null ? state : "unknown");
				Button toggle = new Button(content, SWT.PUSH); boolean connected = "connected".equals(state);
				toggle.setText(connected ? "Disconnect" : "Connect"); toggle.addListener(SWT.Selection, e -> {
					new Thread(() -> { try { if (connected) service.disconnectMcp(name); else service.connectMcp(name);
						content.getDisplay().asyncExec(this::refresh); } catch (Exception ignored) { } }, "opencode-mcp").start();
				});
			}
		} catch (Exception e) { new Label(content, SWT.WRAP).setText("Failed to load MCP status: " + e.getMessage()); }
		content.layout(true, true); content.getShell().pack();
	}
}
