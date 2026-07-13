package com.opencode.eclipse.ui;

import java.util.List;
import com.google.gson.JsonArray;

/** Ordered serialization of selected/custom question answers. */
final class QuestionAnswers {
	private QuestionAnswers() { }
	static JsonArray toJson(List<? extends List<String>> selections, List<String> custom) {
		JsonArray answers = new JsonArray();
		for (int i = 0; i < selections.size(); i++) {
			JsonArray answer = new JsonArray(); selections.get(i).forEach(answer::add);
			if (i < custom.size() && custom.get(i) != null && !custom.get(i).isBlank()) answer.add(custom.get(i).trim());
			answers.add(answer);
		}
		return answers;
	}
}
