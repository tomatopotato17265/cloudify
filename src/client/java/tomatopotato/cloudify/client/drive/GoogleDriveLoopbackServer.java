package tomatopotato.cloudify.client.drive;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class GoogleDriveLoopbackServer implements AutoCloseable {
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final HttpServer server;
	private final String state;
	private final CompletableFuture<String> authorizationCode = new CompletableFuture<>();

	private GoogleDriveLoopbackServer(HttpServer server, String state) {
		this.server = server;
		this.state = state;
	}

	public static GoogleDriveLoopbackServer start() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		byte[] stateBytes = new byte[16];
		SECURE_RANDOM.nextBytes(stateBytes);
		GoogleDriveLoopbackServer loopbackServer = new GoogleDriveLoopbackServer(server, HexFormat.of().formatHex(stateBytes));

		server.createContext("/", loopbackServer::handle);
		server.start();
		return loopbackServer;
	}

	public String redirectUri() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	public String state() {
		return this.state;
	}

	public CompletableFuture<String> authorizationCode() {
		return this.authorizationCode;
	}

	private void handle(HttpExchange exchange) throws IOException {
		Map<String, String> query = parseQuery(exchange.getRequestURI());
		String responseBody;
		int status;

		if (!this.state.equals(query.get("state"))) {
			status = 400;
			responseBody = "Cloudify: this login attempt could not be verified. Please close this tab and try again in Minecraft.";
			this.authorizationCode.completeExceptionally(new IllegalStateException("OAuth state mismatch"));
		} else if (query.containsKey("error")) {
			status = 400;
			responseBody = "Cloudify: Google reported an error (" + query.get("error") + "). Please close this tab and try again in Minecraft.";
			this.authorizationCode.completeExceptionally(new IllegalStateException("OAuth error: " + query.get("error")));
		} else if (query.containsKey("code")) {
			status = 200;
			responseBody = "Cloudify: login successful. You can close this tab and return to Minecraft.";
			this.authorizationCode.complete(query.get("code"));
		} else {
			status = 400;
			responseBody = "Cloudify: no authorization code was received. Please close this tab and try again in Minecraft.";
			this.authorizationCode.completeExceptionally(new IllegalStateException("OAuth redirect missing code"));
		}

		byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(status, responseBytes.length);
		try (OutputStream body = exchange.getResponseBody()) {
			body.write(responseBytes);
		}

		this.server.stop(1);
	}

	private static Map<String, String> parseQuery(URI uri) {
		Map<String, String> result = new HashMap<>();
		String query = uri.getRawQuery();
		if (query == null) {
			return result;
		}

		for (String pair : query.split("&")) {
			int separator = pair.indexOf('=');
			if (separator < 0) {
				continue;
			}
			String key = java.net.URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
			String value = java.net.URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
			result.put(key, value);
		}
		return result;
	}

	@Override
	public void close() {
		this.server.stop(0);
	}
}
