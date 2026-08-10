package tomatopotato.cloudify.client.drive;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public class GoogleDriveAuth {
	public static final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	public static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
	public static final Path TOKENS_DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("cloudify");
}
