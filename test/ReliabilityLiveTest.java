import java.time.Duration;
import java.time.Instant;

import com.opencode.eclipse.core.OpenCodeService;

/** Current-protocol regression: session.status idle and prompt failure unblock SSE. */
public final class ReliabilityLiveTest {
	public static void main(String[] args) throws Exception {
		OpenCodeService service = new OpenCodeService();
		service.initialize(System.getProperty("user.dir"));
		service.setModel("github-copilot/claude-sonnet-4.6");
		service.createSession();
		boolean[] currentIdle = { false };
		service.sendPromptStreaming("Reply with RELIABLE.", event -> {
			if ("session.status".equals(event.type()) && event.isIdle()) currentIdle[0] = true;
		}, null, null);
		assert currentIdle[0] : "current session.status idle not observed";

		service.createSession();
		service.setModel("github-copilot/model-that-does-not-exist");
		Instant start = Instant.now();
		try {
			service.sendPromptStreaming("This must fail.", event -> {}, null, null);
			throw new AssertionError("invalid model unexpectedly succeeded");
		} catch (java.io.IOException expected) {
			assert Duration.between(start, Instant.now()).toSeconds() < 15 : "failure left SSE blocked";
		}
		service.dispose();
		System.out.println("RELIABILITY LIVE OK");
	}
}
