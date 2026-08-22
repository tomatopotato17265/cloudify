package tomatopotato.cloudify.server.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.util.store.FileDataStoreFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.drive.GoogleDriveAuth;
import tomatopotato.cloudify.drive.GoogleDriveConfig;

public class GoogleDriveDeviceAuth {
	private static final String USER_ID = "default";
	private static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
	private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
	private static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

	public static final GoogleAuthorizationCodeFlow FLOW;

	static {
		try {
			FLOW = new GoogleAuthorizationCodeFlow.Builder(
				GoogleDriveAuth.HTTP_TRANSPORT, GoogleDriveAuth.JSON_FACTORY, GoogleDriveConfig.SERVER_CLIENT_ID, GoogleDriveConfig.SERVER_CLIENT_SECRET, GoogleDriveConfig.SCOPES
			)
				.setDataStoreFactory(new FileDataStoreFactory(GoogleDriveAuth.TOKENS_DIRECTORY.resolve("server").toFile()))
				.setAccessType("offline")
				.build();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public record DeviceCode(String deviceCode, String userCode, String verificationUrl, int intervalSeconds, int expiresInSeconds) {
	}

	public enum PollStatus {
		SUCCESS,
		PENDING,
		SLOW_DOWN,
		DENIED
	}

	public record PollResult(PollStatus status, @Nullable Credential credential) {
	}

	public static DeviceCode requestDeviceCode() throws IOException {
		Map<String, String> data = new LinkedHashMap<>();
		data.put("client_id", GoogleDriveConfig.SERVER_CLIENT_ID);
		data.put("scope", String.join(" ", GoogleDriveConfig.SCOPES));

		GenericJson response = executeFormRequest(DEVICE_CODE_URL, data);

		String verificationUrl = (String) response.get("verification_url");
		if (verificationUrl == null) {
			verificationUrl = (String) response.get("verification_uri");
		}

		return new DeviceCode(
			(String) response.get("device_code"),
			(String) response.get("user_code"),
			verificationUrl,
			((Number) response.get("interval")).intValue(),
			((Number) response.get("expires_in")).intValue()
		);
	}

	public static PollResult pollForCredential(String deviceCode) throws IOException {
		Map<String, String> data = new LinkedHashMap<>();
		data.put("client_id", GoogleDriveConfig.SERVER_CLIENT_ID);
		data.put("client_secret", GoogleDriveConfig.SERVER_CLIENT_SECRET);
		data.put("device_code", deviceCode);
		data.put("grant_type", DEVICE_GRANT_TYPE);

		HttpRequestFactory requestFactory = GoogleDriveAuth.HTTP_TRANSPORT.createRequestFactory();
		HttpRequest request = requestFactory.buildPostRequest(new GenericUrl(TOKEN_URL), new UrlEncodedContent(data));
		request.setParser(new JsonObjectParser(GoogleDriveAuth.JSON_FACTORY));
		request.setThrowExceptionOnExecuteError(false);

		HttpResponse response = request.execute();
		if (response.isSuccessStatusCode()) {
			TokenResponse tokenResponse = response.parseAs(TokenResponse.class);
			Credential credential = FLOW.createAndStoreCredential(tokenResponse, USER_ID);
			return new PollResult(PollStatus.SUCCESS, credential);
		}

		GenericJson error = response.parseAs(GenericJson.class);
		String errorCode = (String) error.get("error");
		return switch (errorCode) {
			case "authorization_pending" -> new PollResult(PollStatus.PENDING, null);
			case "slow_down" -> new PollResult(PollStatus.SLOW_DOWN, null);
			default -> new PollResult(PollStatus.DENIED, null);
		};
	}

	private static GenericJson executeFormRequest(String url, Map<String, String> data) throws IOException {
		HttpRequestFactory requestFactory = GoogleDriveAuth.HTTP_TRANSPORT.createRequestFactory();
		HttpRequest request = requestFactory.buildPostRequest(new GenericUrl(url), new UrlEncodedContent(data));
		request.setParser(new JsonObjectParser(GoogleDriveAuth.JSON_FACTORY));
		return request.execute().parseAs(GenericJson.class);
	}

	public static boolean isLoggedIn() throws IOException {
		return FLOW.loadCredential(USER_ID) != null;
	}

	public static Credential getCredential() throws IOException {
		return FLOW.loadCredential(USER_ID);
	}

	public static void logout() throws IOException {
		FLOW.getCredentialDataStore().delete(USER_ID);
	}
}
