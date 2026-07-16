package com.opencode.eclipse.ui;

import java.net.URI;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opencode.eclipse.core.OpenCodeService;

/** Minimal OpenCode provider connection flow for /connect. */
final class ConnectProviderDialog {
	private final Shell shell;
	private final OpenCodeService service;
	private final Runnable connected;

	ConnectProviderDialog(Shell shell, OpenCodeService service, Runnable connected) {
		this.shell = shell; this.service = service; this.connected = connected;
	}

	void open() {
		try {
			JsonObject methods = service.providerAuthMethods();
			String provider = choose("Connect provider", methods.keySet().toArray(String[]::new));
			if (provider == null) return;
			JsonArray providerMethods = methods.getAsJsonArray(provider);
			String[] labels = new String[providerMethods.size()];
			for (int i = 0; i < labels.length; i++) labels[i] = Events.str(providerMethods.get(i).getAsJsonObject(), "label");
			String label = choose("Authentication method", labels); if (label == null) return;
			int index = java.util.Arrays.asList(labels).indexOf(label);
			JsonObject method = providerMethods.get(index).getAsJsonObject();
			JsonObject inputs = inputs(method);
			if (inputs == null) return;
			if ("api".equals(Events.str(method, "type"))) connectApi(provider, inputs);
			else connectOauth(provider, index, inputs);
		} catch (Exception e) {
			org.eclipse.jface.dialogs.MessageDialog.openError(shell, "OpenCode connection", e.getMessage());
		}
	}

	private void connectApi(String provider, JsonObject metadata) throws Exception {
		String key = password("API key", "Enter the API key for " + provider);
		if (key != null && service.setProviderApiKey(provider, key, metadata)) connected.run();
	}

	private String password(String title, String message) {
		final String[] value = { null };
		org.eclipse.jface.dialogs.Dialog dialog = new org.eclipse.jface.dialogs.Dialog(shell) {
			private Text input;
			@Override protected void configureShell(Shell shell) { super.configureShell(shell); shell.setText(title); }
			@Override protected org.eclipse.swt.widgets.Control createDialogArea(org.eclipse.swt.widgets.Composite parent) {
				var area = (org.eclipse.swt.widgets.Composite) super.createDialogArea(parent);
				new org.eclipse.swt.widgets.Label(area, org.eclipse.swt.SWT.WRAP).setText(message);
				input = new Text(area, org.eclipse.swt.SWT.BORDER | org.eclipse.swt.SWT.PASSWORD);
				input.setLayoutData(new org.eclipse.swt.layout.GridData(org.eclipse.swt.SWT.FILL,
						org.eclipse.swt.SWT.CENTER, true, false)); return area;
			}
			@Override protected void okPressed() { value[0] = input.getText(); super.okPressed(); }
		};
		return dialog.open() == Window.OK ? value[0] : null;
	}

	private void connectOauth(String provider, int method, JsonObject inputs) throws Exception {
		JsonObject auth = service.authorizeProvider(provider, method, inputs);
		String url = Events.str(auth, "url");
		String instructions = Events.str(auth, "instructions");
		String authMethod = Events.str(auth, "method");
		if ("code".equals(authMethod)) {
			if (url != null) PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(url).toURL());
			InputDialog code = new InputDialog(shell, "Authorization code",
					instructions, "", null);
			if (code.open() == Window.OK && service.completeProviderAuth(provider, method, code.getValue())) connected.run();
		} else {
			String code = deviceCode(instructions);
			MessageDialog dialog = new MessageDialog(shell, "GitHub authorization", null,
					instructions + "\n\nThe code will be copied before GitHub opens.", MessageDialog.INFORMATION,
					new String[] { "Copy and Open", "Cancel" }, 0);
			if (dialog.open() != 0) return;
			copy(code);
			if (url != null) PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(url).toURL());
			new Thread(() -> {
			try { if (service.completeProviderAuth(provider, method, null)) shell.getDisplay().asyncExec(connected); }
			catch (Exception e) { shell.getDisplay().asyncExec(() -> MessageDialog.openError(shell,
					"OpenCode connection", e.getMessage())); }
			}, "opencode-provider-auth").start();
		}
	}

	static String deviceCode(String instructions) {
		if (instructions == null) return "";
		var matcher = java.util.regex.Pattern.compile("(?i)enter code:\\s*([A-Z0-9-]+)").matcher(instructions);
		return matcher.find() ? matcher.group(1) : instructions;
	}

	private void copy(String value) {
		org.eclipse.swt.dnd.Clipboard clipboard = new org.eclipse.swt.dnd.Clipboard(shell.getDisplay());
		try { clipboard.setContents(new Object[] { value }, new org.eclipse.swt.dnd.Transfer[] {
				org.eclipse.swt.dnd.TextTransfer.getInstance() }); } finally { clipboard.dispose(); }
	}

	private JsonObject inputs(JsonObject method) {
		JsonObject result = new JsonObject(); JsonArray prompts = method.getAsJsonArray("prompts");
		if (prompts == null) return result;
		for (var element : prompts) {
			JsonObject prompt = element.getAsJsonObject(); String key = Events.str(prompt, "key");
			if (!ProviderAuthPrompts.applies(prompt, result)) continue;
			if ("select".equals(Events.str(prompt, "type"))) {
				JsonArray options = prompt.getAsJsonArray("options");
				String[] labels = new String[options.size()];
				for (int i = 0; i < labels.length; i++) labels[i] = Events.str(options.get(i).getAsJsonObject(), "label");
				String label = choose(Events.str(prompt, "message"), labels);
				if (label == null) return null;
				result.addProperty(key, ProviderAuthPrompts.valueForLabel(prompt, label));
			} else {
				InputDialog input = new InputDialog(shell, Events.str(prompt, "message"), Events.str(prompt, "message"), "", null);
				if (input.open() != Window.OK) return null;
				result.addProperty(key, input.getValue());
			}
		}
		return result;
	}

	private String choose(String title, String[] values) {
		ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider());
		dialog.setTitle(title); dialog.setElements(values);
		return dialog.open() == Window.OK ? (String) dialog.getFirstResult() : null;
	}
}
