package com.opencode.eclipse.ui;

import java.util.List;
import java.util.function.Function;

/** Fuzzy subsequence filtering for the model picker. */
final class ModelSearch {
	private ModelSearch() {
	}

	static List<String> filter(List<String> models, String query) {
		return filter(models, query, Function.identity());
	}

	static <T> List<T> filter(List<T> items, String query, Function<T, String> label) {
		String q = query.toLowerCase();
		return items.stream()
				.filter(item -> matches(label.apply(item).toLowerCase(), q))
				.sorted((a, b) -> Integer.compare(score(label.apply(a).toLowerCase(), q),
						score(label.apply(b).toLowerCase(), q)))
				.toList();
	}

	private static boolean matches(String value, String query) {
		int i = 0;
		for (int j = 0; i < query.length() && j < value.length(); j++) {
			if (value.charAt(j) == query.charAt(i)) {
				i++;
			}
		}
		return i == query.length();
	}

	private static int score(String value, String query) {
		if (query.isEmpty()) {
			return 0;
		}
		int first = value.indexOf(query.charAt(0));
		return value.contains(query) ? first : first + value.length();
	}
}
