package com.opencode.eclipse.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** OpenCode question request with ordered single/multiple/custom answers. */
final class QuestionDialog extends Dialog {
	private final JsonArray questions;
	private final List<List<Button>> options = new ArrayList<>();
	private final List<Text> custom = new ArrayList<>();
	private JsonArray answers;

	QuestionDialog(Shell parent, JsonArray questions) { super(parent); this.questions = questions; }

	@Override protected void configureShell(Shell shell) { super.configureShell(shell); shell.setText("OpenCode question"); }

	@Override protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		area.setLayout(new GridLayout(1, false));
		for (JsonElement element : questions) {
			JsonObject question = element.getAsJsonObject();
			Label title = new Label(area, SWT.WRAP); title.setText(Events.str(question, "question"));
			title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			Composite choices = new Composite(area, SWT.NONE);
			choices.setLayout(new GridLayout(1, false));
			choices.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			boolean multiple = question.has("multiple") && question.get("multiple").getAsBoolean();
			List<Button> buttons = new ArrayList<>();
			for (JsonElement optionElement : question.getAsJsonArray("options")) {
				JsonObject option = optionElement.getAsJsonObject();
				Button button = new Button(choices, multiple ? SWT.CHECK : SWT.RADIO);
				button.setText(Events.str(option, "label") + " — " + Events.str(option, "description"));
				button.setData(Events.str(option, "label")); buttons.add(button);
			}
			options.add(buttons);
			boolean allowCustom = !question.has("custom") || question.get("custom").getAsBoolean();
			Text text = new Text(area, SWT.BORDER); text.setMessage(allowCustom ? "Custom answer (optional)" : "");
			text.setEnabled(allowCustom); text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); custom.add(text);
		}
		return area;
	}

	@Override protected void okPressed() {
		List<List<String>> selected = new ArrayList<>();
		List<String> customValues = new ArrayList<>();
		for (int i = 0; i < options.size(); i++) {
			List<String> answer = new ArrayList<>();
			for (Button button : options.get(i)) if (button.getSelection()) answer.add((String) button.getData());
			String customValue = custom.get(i).getText();
			if (answer.isEmpty() && customValue.isBlank()) {
				org.eclipse.jface.dialogs.MessageDialog.openWarning(getShell(), "Answer required",
						"Select or enter an answer for every question.");
				return;
			}
			selected.add(answer); customValues.add(customValue);
		}
		answers = QuestionAnswers.toJson(selected, customValues);
		super.okPressed();
	}

	JsonArray answers() { return answers; }
}
