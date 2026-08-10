package tomatopotato.cloudify.client.drive;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public class GoogleDriveAuth {
	public static final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	public static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
	public static final Path TOKENS_DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("cloudify");
	public static final GoogleAuthorizationCodeFlow FLOW;

	static {
		try {
			FLOW = new GoogleAuthorizationCodeFlow.Builder(
				HTTP_TRANSPORT, JSON_FACTORY, GoogleDriveConfig.CLIENT_ID, GoogleDriveConfig.CLIENT_SECRET, GoogleDriveConfig.SCOPES
			)
				.setDataStoreFactory(new FileDataStoreFactory(TOKENS_DIRECTORY.toFile()))
				.setAccessType("offline")
				.build();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
