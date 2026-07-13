package com.opencode.eclipse.ui;

import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class TodoPanelTest {
	public static void main(String[] args) {
		Display display = new Display(); Shell shell = new Shell(display); shell.setLayout(new GridLayout());
		TodoPanel panel = new TodoPanel(shell); JsonArray todos = new JsonArray();
		todos.add(todo("First", "in_progress", "high")); todos.add(todo("Second", "completed", "medium"));
		panel.setTodos(todos);
		assert panel.getVisible() && panel.getChildren().length == 7;
		assert ((Label) panel.getChildren()[0]).getText().equals("Todos · 1 / 2 completed");
		assert ((Label) panel.getChildren()[2]).getText().equals("First  Current");
		panel.setTodos(new JsonArray()); assert !panel.getVisible();
		shell.dispose(); display.dispose(); System.out.println("TODO PANEL OK");
	}

	private static JsonObject todo(String content, String status, String priority) {
		JsonObject todo = new JsonObject(); todo.addProperty("content", content);
		todo.addProperty("status", status); todo.addProperty("priority", priority); return todo;
	}
}
