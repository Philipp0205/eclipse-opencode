import com.opencode.eclipse.core.OpenCodeService;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SlashCommandLiveTest {
	public static void main(String[] args) throws Exception {
		Path commandDir = Path.of(".opencode", "command");
		Files.createDirectories(commandDir);
		Files.writeString(commandDir.resolve("eclipse_probe.md"),
				"---\ndescription: Eclipse slash command probe\n---\nReply with exactly SLASH_COMMAND_OK.\n");
		OpenCodeService service = new OpenCodeService();
		service.initialize(System.getProperty("user.dir"));
		var commands = service.listCommands();
		assert commands.stream().anyMatch(command -> command.name().equals("eclipse_probe"));
		service.createSession();
		StringBuilder text = new StringBuilder();
		service.executeCommandStreaming("eclipse_probe", "", event -> {
			if ("message.part.updated".equals(event.type())) {
				var part = event.raw().getAsJsonObject("properties").getAsJsonObject("part");
				if (part != null && part.has("text")) { text.setLength(0); text.append(part.get("text").getAsString()); }
			}
		}, null, null);
		service.dispose();
		assert text.toString().contains("SLASH_COMMAND_OK") : text;
		System.out.println("SLASH COMMAND LIVE OK commands=" + commands.size());
	}
}
