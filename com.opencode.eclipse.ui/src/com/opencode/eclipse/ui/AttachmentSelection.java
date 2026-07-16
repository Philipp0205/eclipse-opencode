package com.opencode.eclipse.ui;

import java.util.List;
import java.util.function.Predicate;

final class AttachmentSelection {
	private AttachmentSelection() { }

	static <T> List<T> select(List<T> open, Predicate<T> active, boolean all) {
		return all ? open : open.stream().filter(active).limit(1).toList();
	}
}
