package com.opencode.eclipse.ui;

import java.util.List;

public final class ModelSearchTest {
	public static void main(String[] args) {
		List<String> models = List.of(
				"openai/gpt-5.6",
				"github-copilot/claude-sonnet-4.6",
				"github-copilot/gpt-5.6");
		List<String> result = ModelSearch.filter(models, "ghcp56");
		assert result.equals(List.of("github-copilot/gpt-5.6")) : result;
		assert ModelSearch.filter(models, "sonnet").equals(
				List.of("github-copilot/claude-sonnet-4.6"));
		System.out.println("MODEL SEARCH OK");
	}
}
