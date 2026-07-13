package com.opencode.eclipse.ui;
public final class QuestionAnswersTest {
	public static void main(String[] args) {
		var json = QuestionAnswers.toJson(java.util.List.of(java.util.List.of("Alpha"), java.util.List.of("One", "Two")),
				java.util.List.of("", "custom"));
		assert json.toString().equals("[[\"Alpha\"],[\"One\",\"Two\",\"custom\"]]") : json;
		System.out.println("QUESTION ANSWERS OK");
	}
}
