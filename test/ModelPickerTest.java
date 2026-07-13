package com.opencode.eclipse.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;

public final class ModelPickerTest {
	public static void main(String[] args) {
		Display display = new Display();
		Shell parent = new Shell(display);
		parent.setLayout(new FillLayout());
		Button anchor = new Button(parent, SWT.PUSH);
		ModelPicker picker = new ModelPicker();
		AtomicReference<String> selected = new AtomicReference<>();
		List<String> models = List.of("openai/gpt-5", "github-copilot/claude-sonnet-4.6");
		parent.open();

		picker.toggle(anchor, models, selected::set);
		assert picker.isOpen();
		picker.toggle(anchor, models, selected::set);
		assert !picker.isOpen() : "second click must close popup";

		picker.toggle(anchor, models, selected::set);
		Shell popup = display.getShells()[display.getShells().length - 1];
		org.eclipse.swt.widgets.List results = (org.eclipse.swt.widgets.List) popup.getChildren()[1];
		results.select(1);
		results.notifyListeners(SWT.MouseDown, new Event());
		assert !picker.isOpen() : "selection must close popup";
		assert models.get(1).equals(selected.get()) : selected;

		record Session(String id, String title) { }
		var session = new Session("ses_test", "Session title");
		AtomicReference<Session> selectedSession = new AtomicReference<>();
		picker.toggle(anchor, List.of(session), Session::title, "Search sessions", selectedSession::set);
		popup = display.getShells()[display.getShells().length - 1];
		results = (org.eclipse.swt.widgets.List) popup.getChildren()[1];
		results.select(0); results.notifyListeners(SWT.MouseDown, new Event());
		assert session.equals(selectedSession.get()) : selectedSession;
		assert !picker.isOpen();

		parent.dispose();
		display.dispose();
		System.out.println("MODEL PICKER OK");
	}
}
