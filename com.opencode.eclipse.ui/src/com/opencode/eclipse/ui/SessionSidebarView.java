package com.opencode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

/** A calm workbench companion: session context first, extension space last. */
public final class SessionSidebarView extends ViewPart {
    public static final String ID = "com.opencode.eclipse.ui.sessionSidebarView";
    private Label details;
    private final Runnable refreshListener = this::refresh;
    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        Label heading = new Label(parent, SWT.NONE); heading.setText("SESSION CONTEXT");
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        details = new Label(parent, SWT.WRAP); details.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        details.setText("Open an OpenCode Chat view to inspect its session.");
        Label sections = new Label(parent, SWT.WRAP);
        sections.setText("\nMCP / LSP\nConnected services appear here.");
        sections.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Label plugins = new Label(parent, SWT.WRAP | SWT.SEPARATOR); plugins.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label extension = new Label(parent, SWT.WRAP); extension.setText("LOCAL PLUGINS\nNo local plugin information registered yet.");
        extension.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, true));
        ChatViewRegistry.addListener(refreshListener);
        refresh();
    }
    private void refresh() {
        if (details == null || details.isDisposed()) return;
        ChatView active = ChatViewRegistry.active();
        if (active != null) details.setText(active.sidebarDetails());
        details.getParent().layout(true, true);
    }
    @Override public void setFocus() { if (details != null) details.setFocus(); }
    @Override public void dispose() { ChatViewRegistry.removeListener(refreshListener); super.dispose(); }
}
