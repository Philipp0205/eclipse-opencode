import com.google.gson.JsonArray;
import com.opencode.eclipse.core.OpenCodeService;

public final class QuestionLiveTest {
	public static void main(String[] args) throws Exception {
		OpenCodeService service = new OpenCodeService();
		service.initialize(System.getProperty("user.dir"));
		service.setModel("github-copilot/claude-sonnet-4.6"); service.createSession();
		boolean[] asked = { false };
		service.sendPromptStreaming("Use the question tool to ask exactly one question with options Alpha and Beta, then finish.", event -> {
			if (!"question.asked".equals(event.type())) return;
			asked[0] = true;
			var props = event.raw().getAsJsonObject("properties");
			JsonArray answer = new JsonArray(); JsonArray selected = new JsonArray(); selected.add("Alpha"); answer.add(selected);
			try { assert service.replyQuestion(props.get("id").getAsString(), answer); }
			catch (Exception e) { throw new RuntimeException(e); }
		}, null, null);
		service.dispose(); assert asked[0] : "question event not received";
		System.out.println("QUESTION LIVE OK");
	}
}
