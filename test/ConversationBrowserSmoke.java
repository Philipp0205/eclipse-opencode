package com.opencode.eclipse.ui;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ConversationBrowserSmoke {
	public static void main(String[] args) throws Exception {
		var messages = syntheticConversation();
		Display display = new Display();
		Shell shell = new Shell(display);
		shell.setLayout(new FillLayout()); shell.setSize(700, 600);
		ConversationBrowser browser = new ConversationBrowser(shell);
		shell.open();
		long ready = System.currentTimeMillis() + 1200;
		while (System.currentTimeMillis() < ready) if (!display.readAndDispatch()) display.sleep();
		assert Boolean.TRUE.equals(browser.evaluate("return !document.getElementById('welcome').hidden;"));
		Object welcome = browser.evaluate("return document.getElementById('welcome').innerText;");
		assert welcome instanceof String && ((String) welcome).contains("Ask OpenCode to edit your files")
				&& ((String) welcome).contains("Philipp Kurrle") : welcome;
		assert Boolean.TRUE.equals(browser.evaluate("return document.querySelector('#welcome a') === null;"));
		browser.setConversation(messages);
		long end = System.currentTimeMillis() + 2500;
		while (System.currentTimeMillis() < end) if (!display.readAndDispatch()) display.sleep();
		assert !browser.isDisposed();
		Object count = browser.evaluate("return document.querySelectorAll('.message').length;");
		assert count instanceof Double && ((Double) count).intValue() == 300 : count;
		Object text = browser.evaluate("return document.getElementById('conversation').innerText;");
		assert text instanceof String && ((String) text).contains("FINAL_BROWSER_MARKER") : text;
		assert Boolean.TRUE.equals(browser.evaluate("return document.querySelectorAll('.todo').length === 2;"));
		assert Boolean.TRUE.equals(browser.evaluate("return document.querySelectorAll('table').length > 0;"));
		assert Boolean.TRUE.equals(browser.evaluate("return document.querySelectorAll('.thinking').length > 0;"));
		Object highlighted = browser.evaluate("return document.querySelectorAll('.hl-keyword').length;");
		assert highlighted instanceof Double && ((Double) highlighted).intValue() >= 2 : highlighted + " / "
				+ browser.evaluate("return document.querySelector('code') ? document.querySelector('code').outerHTML : 'missing';");
		assert Boolean.TRUE.equals(browser.evaluate("return document.getElementById('welcome').hidden;"));
		shell.dispose(); display.dispose();
		System.out.println("CONVERSATION BROWSER OK messages=" + messages.size());
	}

	private static JsonArray syntheticConversation() {
		JsonArray messages = new JsonArray();
		for (int i = 0; i < 300; i++) {
			JsonObject message = new JsonObject();
			JsonObject info = new JsonObject();
			info.addProperty("id", "msg_" + i);
			info.addProperty("role", i % 2 == 0 ? "user" : "assistant");
			message.add("info", info);
			JsonArray parts = new JsonArray();
			JsonObject text = new JsonObject();
			text.addProperty("type", "text");
			text.addProperty("text", i == 299 ? "FINAL_BROWSER_MARKER" : "Message **" + i + "**");
			parts.add(text);
			if (i == 1) {
				JsonObject reasoning = new JsonObject(); reasoning.addProperty("type", "reasoning");
				reasoning.addProperty("text", "Inspecting the implementation"); parts.add(reasoning);
			}
			if (i == 3) parts.add(todoPart());
			if (i == 5) text.addProperty("text", "| A | B |\n|---|---|\n| 1 | 2 |");
			if (i == 7) text.addProperty("text", "```java\npublic class Demo {}\n```");
			message.add("parts", parts); messages.add(message);
		}
		return messages;
	}

	private static JsonObject todoPart() {
		JsonObject part = new JsonObject(); part.addProperty("type", "tool"); part.addProperty("tool", "todowrite");
		JsonObject state = new JsonObject(); JsonObject input = new JsonObject(); JsonArray todos = new JsonArray();
		for (String status : new String[] { "in_progress", "completed" }) {
			JsonObject todo = new JsonObject(); todo.addProperty("content", status + " item");
			todo.addProperty("status", status); todo.addProperty("priority", "high"); todos.add(todo);
		}
		input.add("todos", todos); state.add("input", input); part.add("state", state); return part;
	}

}
