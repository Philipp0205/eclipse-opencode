import com.opencode.eclipse.core.OpenCodeService;

/** Live regression check: abort unblocks SSE and the same session accepts another prompt. */
public final class AbortContinueTest {
	public static void main(String[] args) throws Exception {
		OpenCodeService service = new OpenCodeService();
		service.initialize(System.getProperty("user.dir"));
		service.setModel("github-copilot/claude-sonnet-4.6");
		service.createSession();

		Thread first = new Thread(() -> {
			try {
				service.sendPromptStreaming("Count slowly from 1 to 1000.", event -> {}, null, null);
			} catch (Exception expectedAfterAbort) {
				// Closing the SSE stream is the cancellation signal.
			}
		});
		first.start();
		Thread.sleep(2000);
		service.abortSession(null);
		first.join(5000);
		assert !first.isAlive() : "abort did not unblock SSE";

		StringBuilder reply = new StringBuilder();
		service.sendPromptStreaming("Reply with CONTINUED.", event -> {
			if (event.type().equals("message.part.updated")) {
				var part = event.raw().getAsJsonObject("properties").getAsJsonObject("part");
				if (part != null && part.has("text")) {
					reply.setLength(0);
					reply.append(part.get("text").getAsString());
				}
			}
		}, null, null);
		service.dispose();
		assert reply.toString().contains("CONTINUED") : reply;
		System.out.println("ABORT CONTINUE OK");
	}
}
