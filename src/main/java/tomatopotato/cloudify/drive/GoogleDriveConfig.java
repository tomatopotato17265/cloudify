package tomatopotato.cloudify.drive;

import com.google.api.services.drive.DriveScopes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

public class GoogleDriveConfig {
	public static final String CLIENT_ID;
	public static final String CLIENT_SECRET;
	public static final String SERVER_CLIENT_ID;
	public static final String SERVER_CLIENT_SECRET;
	public static final List<String> SCOPES = List.of(DriveScopes.DRIVE_FILE, "https://www.googleapis.com/auth/userinfo.email");

	static {
		Properties properties = new Properties();
		try (InputStream stream = GoogleDriveConfig.class.getResourceAsStream("/cloudify-google-drive.properties")) {
			properties.load(stream);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		CLIENT_ID = properties.getProperty("client_id");
		CLIENT_SECRET = properties.getProperty("client_secret");
		SERVER_CLIENT_ID = properties.getProperty("server_client_id");
		SERVER_CLIENT_SECRET = properties.getProperty("server_client_secret");
	}
}
