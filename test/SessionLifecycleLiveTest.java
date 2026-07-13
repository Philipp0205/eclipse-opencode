import com.opencode.eclipse.core.OpenCodeService;

public final class SessionLifecycleLiveTest {
	public static void main(String[] args) throws Exception {
		OpenCodeService service = new OpenCodeService();
		service.initialize(System.getProperty("user.dir"));
		String parent = service.createSession();
		assert java.nio.file.Path.of(service.getCurrentSessionDirectory()).toRealPath()
				.equals(java.nio.file.Path.of(System.getProperty("user.dir")).toRealPath())
				: service.getCurrentSessionDirectory();
		assert "Eclipse lifecycle probe".equals(service.renameSession(parent, "Eclipse lifecycle probe").get("title").getAsString());
		service.setModel("github-copilot/claude-sonnet-4.6");
		service.sendPromptStreaming("Reply with FORK_READY.", event -> {}, parent, null);
		service.compactSession(parent);
		var fork = service.forkSession(parent, null);
		String child = fork.get("id").getAsString();
		var children = service.getSessionChildren(parent);
		assert child.startsWith("ses_") : fork;
		System.out.println("fork=" + fork + " children=" + children.size());
		assert service.getSessionTodos(parent) != null;
		assert service.deleteSession(child);
		assert service.deleteSession(parent);
		service.dispose();
		System.out.println("SESSION LIFECYCLE LIVE OK");
	}
}
