package com.opencode.eclipse.ui;

import java.util.List;

public final class AttachmentSelectionTest {
	private record Item(String name, boolean active) { }

	public static void main(String[] args) {
		var active = new Item("active", true); var other = new Item("other", false);
		assert AttachmentSelection.select(List.of(active, other), Item::active, false).equals(List.of(active));
		assert AttachmentSelection.select(List.of(active, other), Item::active, true).equals(List.of(active, other));
		System.out.println("ATTACHMENT SELECTION OK");
	}
}
