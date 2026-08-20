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
		assert Boolean.TRUE.equals(browser.evaluate("return document.getElementById('jump-to-latest').hidden;"));
		browser.evaluate("window.scrollTo(0, 0);");
		long scrollCheck = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < scrollCheck) if (!display.readAndDispatch()) display.sleep();
		assert Boolean.FALSE.equals(browser.evaluate("return document.getElementById('jump-to-latest').hidden;"));
		browser.evaluate("window.scrollTo(0, document.body.scrollHeight - 32);");
		long nearBottomCheck = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < nearBottomCheck) if (!display.readAndDispatch()) display.sleep();
		assert Boolean.TRUE.equals(browser.evaluate("return window.innerHeight + window.scrollY >= document.body.scrollHeight - 64 && document.getElementById('jump-to-latest').hidden;"));
		browser.evaluate("window.scrollTo(0, document.body.scrollHeight);");
		long programmaticScrollCheck = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < programmaticScrollCheck) if (!display.readAndDispatch()) display.sleep();
		assert Boolean.TRUE.equals(browser.evaluate("pinned = false; updateJumpToLatest(); return document.getElementById('jump-to-latest').hidden;"));
		browser.evaluate("window.scrollTo(0, 0);");
		long returnToTopCheck = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < returnToTopCheck) if (!display.readAndDispatch()) display.sleep();
		browser.evaluate("document.getElementById('jump-to-latest').click();");
		long bottomCheck = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < bottomCheck) if (!display.readAndDispatch()) display.sleep();
		assert Boolean.TRUE.equals(browser.evaluate("return window.innerHeight + window.scrollY >= document.body.scrollHeight - 64 && document.getElementById('jump-to-latest').hidden;"));
		Object text = browser.evaluate("return document.getElementById('conversation').innerText;");
		assert text instanceof String && ((String) text).contains("FINAL_BROWSER_MARKER") : text;
		assert Boolean.TRUE.equals(browser.evaluate("return document.querySelectorAll('.tool').length > 0;"));
		assert Boolean.TRUE.equals(browser.evaluate("return document.querySelectorAll('table').length > 0;"));
		assert Boolean.TRUE.equals(browser.evaluate("return document.querySelectorAll('.thinking').length > 0;"));
		Object highlighted = browser.evaluate("return document.querySelectorAll('.hl-keyword').length;");
		assert highlighted instanceof Double && ((Double) highlighted).intValue() >= 2 : highlighted + " / "
				+ browser.evaluate("return document.querySelector('code') ? document.querySelector('code').outerHTML : 'missing';");
		assert Boolean.TRUE.equals(browser.evaluate("return document.getElementById('welcome').hidden;"));
		browser.evaluate("window.scrollTo(0, 0);");
		long scrollTopCheck = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < scrollTopCheck) if (!display.readAndDispatch()) display.sleep();
		browser.scrollPage(false);
		long pageDownCheck = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < pageDownCheck) if (!display.readAndDispatch()) display.sleep();
		Object afterPageDown = browser.evaluate("return window.scrollY;");
		assert afterPageDown instanceof Double && ((Double) afterPageDown) > 0 : afterPageDown;
		browser.scrollPage(true);
		long pageUpCheck = System.currentTimeMillis() + 500;
		while (System.currentTimeMillis() < pageUpCheck) if (!display.readAndDispatch()) display.sleep();
		Object afterPageUp = browser.evaluate("return window.scrollY;");
		assert afterPageUp instanceof Double && ((Double) afterPageUp) < ((Double) afterPageDown) : afterPageUp + " / " + afterPageDown;
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
			if (i == 3) parts.add(toolPart());
			if (i == 5) text.addProperty("text", "| A | B |\n|---|---|\n| 1 | 2 |");
			if (i == 7) text.addProperty("text", "```java\npublic class Demo {}\n```");
			message.add("parts", parts); messages.add(message);
		}
		return messages;
	}

	private static JsonObject toolPart() {
		JsonObject part = new JsonObject(); part.addProperty("type", "tool"); part.addProperty("tool", "read");
		JsonObject state = new JsonObject(); JsonObject input = new JsonObject();
		input.addProperty("filePath", "/tmp/smoke.txt"); state.add("input", input);
		state.addProperty("status", "completed"); part.add("state", state); return part;
	}

}
