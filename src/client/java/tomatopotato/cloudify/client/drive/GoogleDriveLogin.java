package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

public class GoogleDriveLogin {
	private static final String USER_ID = "default";

	public static URI buildAuthorizationUrl(GoogleDriveLoopbackServer server) {
		String authorizationUrl = new GoogleAuthorizationCodeRequestUrl(GoogleDriveConfig.CLIENT_ID, server.redirectUri(), GoogleDriveConfig.SCOPES)
			.setState(server.state())
			.setAccessType("offline")
			.set("prompt", "consent")
			.build();

		return URI.create(authorizationUrl);
	}

	public static void openAuthorizationPage(GoogleDriveLoopbackServer server) throws IOException {
		Desktop.getDesktop().browse(buildAuthorizationUrl(server));
	}

	public static Credential exchangeCode(GoogleDriveLoopbackServer server, String code) throws IOException {
		TokenResponse tokenResponse = GoogleDriveAuth.FLOW.newTokenRequest(code).setRedirectUri(server.redirectUri()).execute();
		return GoogleDriveAuth.FLOW.createAndStoreCredential(tokenResponse, USER_ID);
	}
}
