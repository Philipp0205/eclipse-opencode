import com.opencode.eclipse.core.OpenCodeService;
import com.opencode.eclipse.core.OpenCodeEvent;
import com.google.gson.JsonObject;

/** Live integration check: valid model streams assistant text; bad model surfaces an error. */
public class ItTest {
	static String str(JsonObject o, String k){ return o!=null&&o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():null; }

	public static void main(String[] a) throws Exception {
		String model = a.length > 0 ? a[0] : "github-copilot/claude-sonnet-4.6";
		OpenCodeService svc = new OpenCodeService();
		svc.initialize(System.getProperty("user.dir"));

		// agents present & primary-filtered
		var agents = svc.getAgents();
		System.out.println("agents=" + agents.size());
		assert agents.size() > 0 : "no agents";
		assert agents.stream().noneMatch(agent -> java.util.Set.of("compaction", "summary", "title")
				.contains(agent.get("name").getAsString())) : agents;
		var providerStatus = svc.providerStatus();
		assert providerStatus.has("connected") && providerStatus.has("all") : providerStatus;

		svc.createSession();

		// replay events exactly like ChatView.onEvent, using messageID+role tracking
		java.util.Map<String,String> roles = new java.util.HashMap<>();
		StringBuilder assistant = new StringBuilder();
		StringBuilder error = new StringBuilder();
		boolean[] idle = {false};

		svc.sendPromptStreaming("Reply with exactly one word: HELLO", ev -> {
			JsonObject raw = ev.raw();
			switch (ev.type()) {
			case "message.updated" -> {
				var info = raw.getAsJsonObject("properties").getAsJsonObject("info");
				if (info!=null) roles.put(str(info,"id"), str(info,"role"));
			}
			case "message.part.updated" -> {
				var part = raw.getAsJsonObject("properties").getAsJsonObject("part");
				if (part!=null && "text".equals(str(part,"type")) && part.has("text")) {
					String mid = str(part,"messageID");
					if (!"user".equals(roles.get(mid))) { assistant.setLength(0); assistant.append(part.get("text").getAsString()); }
				}
			}
			case "session.error" -> {
				var e = raw.getAsJsonObject("properties").getAsJsonObject("error");
				var d = e!=null?e.getAsJsonObject("data"):null;
				error.append(d!=null?str(d,"message"):str(e,"name"));
			}
			case "session.idle" -> idle[0]=true;
			default -> {}
			}
		}, null, null);

		System.out.println("idle=" + idle[0]);
		System.out.println("assistant=[" + assistant + "]");
		System.out.println("error=[" + error + "]");
		svc.dispose();

		if (model.contains("sonnect")) { // intentional-typo run
			assert error.length() > 0 : "expected an error for bad model";
			System.out.println("BAD-MODEL OK (error surfaced)");
		} else {
			assert assistant.toString().toUpperCase().contains("HELLO") : "assistant text missing";
			System.out.println("GOOD-MODEL OK (assistant rendered)");
		}
	}
}
