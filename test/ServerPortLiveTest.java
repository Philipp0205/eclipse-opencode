package com.opencode.eclipse.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Live check that each service owns a private port. Needs {@code opencode} on {@code PATH} but no
 * provider and no model request.
 *
 * <p>opencode stopped honouring {@code --port 0}: it binds its default port 4096 and only picks a
 * random one when 4096 is taken. Several chat views therefore have to be given explicit ports, or
 * the first one claims the port the user's own TUI/desktop expects to own.
 */
public final class ServerPortLiveTest {
	private static final int OPENCODE_DEFAULT_PORT = 4096;

	public static void main(String[] args) throws Exception {
		String directory = System.getProperty("user.dir");
		List<OpenCodeService> services = new ArrayList<>();
		try {
			for (int i = 0; i < 2; i++) {
				OpenCodeService service = new OpenCodeService();
				services.add(service);
				service.initialize(directory);
			}
			List<String> urls = new ArrayList<>();
			for (OpenCodeService service : services) {
				assert service.isReady();
				assert service.getServerVersion() != null : "no version from /global/health";
				String url = listenUrl(service);
				assert url != null : "no listen URL in: " + service.serverOutput();
				assert !url.endsWith(":" + OPENCODE_DEFAULT_PORT) : "claimed opencode's default port: " + url;
				urls.add(url);
				System.out.println("server " + urls.size() + " on " + url + " (" + service.getServerVersion() + ")");
			}
			assert urls.get(0) != null && !urls.get(0).equals(urls.get(1)) : urls;
		} finally {
			services.forEach(OpenCodeService::dispose);
		}
		System.out.println("SERVER PORT OK");
	}

	private static String listenUrl(OpenCodeService service) {
		for (String line : service.serverOutput().split("\n")) {
			String url = OpenCodeService.parseListenUrl(line, 0);
			if (url != null) return url;
		}
		return null;
	}
}
