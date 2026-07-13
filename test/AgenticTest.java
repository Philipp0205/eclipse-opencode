import com.opencode.eclipse.core.OpenCodeService;
import com.google.gson.JsonObject;

/** Live check: model override is applied, and getDiff reports a file opencode edits. */
public class AgenticTest {
	static String str(JsonObject o,String k){return o!=null&&o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():null;}
	public static void main(String[] a) throws Exception {
		var s = new OpenCodeService();
		s.initialize(System.getProperty("user.dir"));

		// providers non-empty
		var provs = s.listProviders().getAsJsonArray("providers");
		assert provs != null && provs.size() > 0 : "no providers";
		System.out.println("providers=" + provs.size());

		// pick a concrete github-copilot model and set override
		s.setModel("github-copilot/claude-sonnet-4.6");
		assert "github-copilot/claude-sonnet-4.6".equals(s.getModel());

		s.createSession();

		// ask it to create a file, then assert diff reports it
		boolean[] idle={false};
		java.util.List<String> edited = new java.util.ArrayList<>();
		s.sendPromptStreaming(
			"Create a new file named agentic_probe.txt in the current directory containing exactly: PROBE_OK",
			ev -> {
				if (ev.type().equals("file.edited")) {
					var f = ev.raw().getAsJsonObject("properties");
					edited.add(f!=null?str(f,"file"):null);
				}
				if (ev.type().equals("session.idle")) idle[0]=true;
			}, null, null);

		System.out.println("idle=" + idle[0] + " edited=" + edited);
		var diff = s.getDiff(null);
		System.out.println("diff files=" + diff.size());
		for (int i=0;i<diff.size();i++){
			var d=diff.get(i).getAsJsonObject();
			System.out.println("  " + str(d,"file") + " (" + str(d,"status") + ")");
		}
		s.dispose();
		assert diff.size() > 0 || !edited.isEmpty() : "no edits detected";
		System.out.println("AGENTIC OK");
	}
}
