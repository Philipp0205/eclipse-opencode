package com.opencode.eclipse.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ChildSessionTrackerTest {
	public static void main(String[] args) {
		assert ChildSessionTracker.mapChildStatus(null).equals("running");
		assert ChildSessionTracker.mapChildStatus("completed").equals("done");
		assert ChildSessionTracker.mapChildStatus("FAILED").equals("error");
		assert ChildSessionTracker.mapChildStatus("cancelled").equals("cancelled");
		assert ChildSessionTracker.mapChildStatus("weird").equals("running");
		assert ChildSessionTracker.isTerminalChildStatus("done");
		assert ChildSessionTracker.isTerminalChildStatus("error");
		assert !ChildSessionTracker.isTerminalChildStatus("running");

		JsonObject withStatus = JsonParser.parseString("{\"status\":\"completed\"}").getAsJsonObject();
		assert ChildSessionTracker.descendantStatus(withStatus).equals("done");
		JsonObject withCompletedTime = JsonParser.parseString("{\"time\":{\"completed\":123}}").getAsJsonObject();
		assert ChildSessionTracker.descendantStatus(withCompletedTime).equals("done");
		JsonObject running = JsonParser.parseString("{}").getAsJsonObject();
		assert ChildSessionTracker.descendantStatus(running).equals("running");

		JsonObject nested = JsonParser.parseString(
				"{\"foo\":{\"bar\":{\"sessionId\":\"child-1\"}}}").getAsJsonObject();
		assert "child-1".equals(ChildSessionTracker.findSessionId(nested));
		assert ChildSessionTracker.findSessionId(null) == null;

		Object ownerA = new Object();
		Object ownerB = new Object();
		JsonObject taskPart = JsonParser.parseString(
				"{\"state\":{\"status\":\"running\",\"input\":{\"subagent_type\":\"explore\",\"description\":\"look around\"},"
						+ "\"metadata\":{\"sessionId\":\"child-42\"}}}").getAsJsonObject();
		assert ChildSessionTracker.track(ownerA, taskPart, "parent-session", "/dir");
		assert ChildSessionTracker.infoFor(ownerA).size() == 1;
		var info = ChildSessionTracker.infoFor(ownerA).get(0);
		assert info.id().equals("child-42");
		assert info.agent().equals("explore");
		assert info.title().equals("look around");
		assert info.status().equals("running");

		// A different owner can't steal an already-tracked child.
		assert !ChildSessionTracker.track(ownerB, taskPart, "parent-session", "/dir");
		assert ChildSessionTracker.infoFor(ownerB).isEmpty();

		// Re-tracking with the same content is not a "change".
		assert !ChildSessionTracker.track(ownerA, taskPart, "parent-session", "/dir");

		assert ChildSessionTracker.isTrackedBy(ownerA, "child-42");
		assert !ChildSessionTracker.isTrackedBy(ownerB, "child-42");

		JsonObject completedTaskPart = JsonParser.parseString(
				"{\"state\":{\"status\":\"completed\",\"input\":{},\"metadata\":{\"sessionId\":\"child-42\"}}}").getAsJsonObject();
		assert ChildSessionTracker.track(ownerA, completedTaskPart, "parent-session", "/dir");
		assert ChildSessionTracker.infoFor(ownerA).isEmpty();

		JsonObject taskPart2 = JsonParser.parseString(
				"{\"state\":{\"status\":\"running\",\"input\":{},\"metadata\":{\"sessionId\":\"child-99\"}}}").getAsJsonObject();
		ChildSessionTracker.track(ownerA, taskPart2, "parent-session", "/dir");
		assert !ChildSessionTracker.infoFor(ownerA).isEmpty();
		ChildSessionTracker.releaseOwner(ownerA);
		assert ChildSessionTracker.infoFor(ownerA).isEmpty();

		System.out.println("CHILD SESSION TRACKER OK");
	}
}
