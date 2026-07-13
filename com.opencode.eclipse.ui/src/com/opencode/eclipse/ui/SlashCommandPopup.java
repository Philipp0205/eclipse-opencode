package com.opencode.eclipse.ui;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.opencode.eclipse.core.CommandInfo;

/** Non-focus-stealing slash-command suggestions anchored above the prompt. */
final class SlashCommandPopup {
	private final Text input;
	private final Consumer<CommandInfo> selection;
	private Shell shell;
	private Composite rows;
	private Label hint;
	private List<CommandInfo> matches = List.of();
	private int selectedIndex;

	SlashCommandPopup(Text input, Consumer<CommandInfo> selection) {
		this.input = input;
		this.selection = selection;
	}

	void update(List<CommandInfo> commands) {
		matches = SlashCommands.filter(commands, input.getText());
		if (matches.isEmpty()) { close(); return; }
		ensureOpen();
		renderRows();
		selectedIndex = 0;
		updateHint();
		input.getDisplay().asyncExec(input::forceFocus);
	}

	boolean handleKey(Event event) {
		if (!isOpen()) return false;
		if (event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.ARROW_UP) {
			selectedIndex = Math.max(0, Math.min(matches.size() - 1,
					selectedIndex + (event.keyCode == SWT.ARROW_DOWN ? 1 : -1)));
			renderRows();
			updateHint();
			return true;
		}
		if (event.keyCode == SWT.CR || event.keyCode == SWT.TAB) { commit(); return true; }
		if (event.keyCode == SWT.ESC) { close(); return true; }
		return false;
	}

	void close() {
		if (shell != null && !shell.isDisposed()) shell.dispose();
		shell = null;
	}

	private void ensureOpen() {
		if (isOpen()) return;
		shell = new Shell(input.getShell(), SWT.ON_TOP | SWT.NO_TRIM | SWT.NO_FOCUS | SWT.BORDER);
		shell.setLayout(new GridLayout(1, false));
		rows = new Composite(shell, SWT.NONE);
		rows.setLayout(new GridLayout(1, false));
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true); data.widthHint = 480; data.heightHint = 220;
		rows.setLayoutData(data);
		hint = new Label(shell, SWT.WRAP); hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		shell.pack();
		Point at = input.toDisplay(0, -shell.getSize().y);
		shell.setLocation(at);
		shell.open();
		input.getDisplay().asyncExec(input::forceFocus);
	}

	private void commit() {
		if (selectedIndex >= 0 && selectedIndex < matches.size()) {
			CommandInfo command = matches.get(selectedIndex); close(); selection.accept(command);
		}
	}

	private void updateHint() {
		if (selectedIndex < 0 || selectedIndex >= matches.size()) return;
		CommandInfo command = matches.get(selectedIndex);
		hint.setText(command.hints().isEmpty() ? command.description() : String.join(" ", command.hints()));
		shell.layout(true, true);
	}

	private void renderRows() {
		if (rows == null || rows.isDisposed()) return;
		for (var child : rows.getChildren()) child.dispose();
		for (int i = 0; i < matches.size(); i++) {
			int index = i;
			Label row = new Label(rows, SWT.NONE);
			row.setText((i == selectedIndex ? "› " : "  ") + label(matches.get(i)));
			row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			row.addListener(SWT.MouseEnter, e -> { selectedIndex = index; renderRows(); updateHint(); });
			row.addListener(SWT.MouseUp, e -> { selectedIndex = index; commit(); });
		}
		rows.layout(true, true);
	}

	private String label(CommandInfo command) {
		String source = command.source() != null ? command.source().toUpperCase() : "COMMAND";
		return "/" + command.name() + "   [" + source + "]   " + command.description();
	}

	private boolean isOpen() { return shell != null && !shell.isDisposed() && shell.isVisible(); }
}
