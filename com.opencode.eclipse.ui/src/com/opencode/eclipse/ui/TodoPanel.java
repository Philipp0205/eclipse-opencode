package com.opencode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Current session task progress, separate from historical chat messages. */
final class TodoPanel extends Composite {
	TodoPanel(Composite parent) {
		super(parent, SWT.BORDER);
		setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		setVisible(false); ((GridData) getLayoutData()).exclude = true;
	}

	void setTodos(JsonArray todos) {
		for (var child : getChildren()) child.dispose();
		boolean visible = todos != null && !todos.isEmpty();
		setVisible(visible); ((GridData) getLayoutData()).exclude = !visible;
		if (visible) {
			setLayout(new GridLayout(3, false));
			int completed = 0; int actionable = 0;
			for (JsonElement element : todos) {
				String state = Events.str(element.getAsJsonObject(), "status");
				if (!"cancelled".equals(state)) actionable++;
				if ("completed".equals(state)) completed++;
			}
			Label title = new Label(this, SWT.NONE); title.setText("Todos · " + completed + " / " + actionable + " completed");
			title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
			for (JsonElement element : todos) add(element.getAsJsonObject());
		}
		getParent().layout(true, true);
	}

	private void add(JsonObject todo) {
		String state = Events.str(todo, "status");
		Label icon = new Label(this, SWT.NONE);
		icon.setText("completed".equals(state) ? "\u2713" : "in_progress".equals(state) ? "\u25cf"
				: "cancelled".equals(state) ? "\u00d7" : "\u25cb");
		Label text = new Label(this, SWT.NONE);
		text.setText(Events.str(todo, "content") + ("in_progress".equals(state) ? "  Current" : ""));
		text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Label priority = new Label(this, SWT.NONE); priority.setText(Events.str(todo, "priority"));
	}
}
