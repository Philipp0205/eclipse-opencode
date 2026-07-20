package com.opencode.eclipse.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

/** One Browser for the complete conversation with small incremental DOM updates. */
final class ConversationBrowser extends Composite {
	private static final Gson JSON = new Gson();
	private final Browser browser;
	private final List<String> pending = new ArrayList<>();
	private boolean loaded;

	ConversationBrowser(Composite parent) {
		super(parent, SWT.NONE);
		setLayout(new FillLayout());
		Browser created = null;
		try { created = new Browser(this, SWT.NONE); } catch (RuntimeException ignored) { }
		browser = created;
		if (browser == null) {
			Label error = new Label(this, SWT.WRAP);
			error.setText("OpenCode chat could not start the SWT Browser. Install WebKitGTK and restart Eclipse.");
			return;
		}
		browser.setJavascriptEnabled(true);
		browser.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
			@Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent e) { e.result = "OpenCode conversation"; }
		});
		browser.addLocationListener(new LocationAdapter() {
			@Override public void changing(LocationEvent event) {
				if (event.location != null && event.location.matches("https?://.*")) {
					event.doit = false;
					try { org.eclipse.ui.PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser()
							.openURL(java.net.URI.create(event.location).toURL()); } catch (Exception ignored) { }
				} else if (loaded && event.location != null && !event.location.startsWith("about:blank")) event.doit = false;
			}
		});
		browser.addProgressListener(new ProgressAdapter() {
			@Override
			public void completed(ProgressEvent event) {
				loaded = true;
				for (String script : List.copyOf(pending)) browser.execute(script);
				pending.clear();
			}
		});
		browser.setText(page());
	}

	void setConversation(JsonArray messages) {
		execute("reset(" + JSON.toJson(ConversationHtml.conversation(messages)) + ")");
	}

	void setChatFontSize(int points) {
		execute("document.documentElement.style.setProperty('--chat-font-size', '" + Math.max(10, Math.min(24, points)) + "px')");
	}

	void putMessage(String id, String role, String markdown) {
		put(id, ConversationHtml.liveMessage(id, role, markdown));
	}

	void putMessageHtml(String id, String html) {
		put(id, html);
	}

	void showActivity(String id) {
		put(id, ConversationHtml.activity(id));
	}

	void remove(String id) {
		execute("removeBlock(" + JSON.toJson(ConversationHtml.id(id)) + ")");
	}

	void clear() {
		execute("reset('')");
	}

	Object evaluate(String script) {
		return browser != null && loaded ? browser.evaluate(script) : null;
	}

	private void put(String id, String html) {
		execute("put(" + JSON.toJson(ConversationHtml.id(id)) + "," + JSON.toJson(html) + ")");
	}

	private void execute(String script) {
		if (isDisposed() || browser == null || browser.isDisposed()) return;
		if (loaded) browser.execute(script); else pending.add(script);
	}

	private static String page() {
		try {
			String html = resource("resources/chat.html");
			return html.replace("__CSS__", resource("resources/chat.css"));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load chat browser resources", e);
		}
	}

	private static String resource(String path) throws IOException {
		try (var stream = ConversationBrowser.class.getClassLoader().getResourceAsStream(path)) {
			if (stream == null) throw new IOException("Missing " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
