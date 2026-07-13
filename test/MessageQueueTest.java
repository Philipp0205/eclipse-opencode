package com.opencode.eclipse.ui;

public final class MessageQueueTest {
	public static void main(String[] args) {
		MessageQueue<String> queue = new MessageQueue<>();
		queue.add("first"); queue.add("second"); queue.add("third");
		assert queue.snapshot().equals(java.util.List.of("first", "second", "third"));
		assert queue.remove("second");
		assert "first".equals(queue.poll()); assert "third".equals(queue.poll()); assert queue.isEmpty();
		System.out.println("MESSAGE QUEUE OK");
	}
}
