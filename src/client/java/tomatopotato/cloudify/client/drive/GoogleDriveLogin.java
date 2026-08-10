package tomatopotato.cloudify.client.drive;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

public class GoogleDriveLogin {
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
}
