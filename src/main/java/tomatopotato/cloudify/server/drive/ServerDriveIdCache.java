package tomatopotato.cloudify.server.drive;

import com.google.api.client.json.GenericJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.drive.DriveTreeSync;
import tomatopotato.cloudify.drive.GoogleDriveAuth;

public class ServerDriveIdCache {
	private static final Path CACHE_FILE = GoogleDriveAuth.TOKENS_DIRECTORY.resolve("server").resolve("drive-cache.json");

	public record ServerState(String folderId, DriveTreeSync.DriveManifest manifest) {
	}

	public record Data(@Nullable String cloudifyFolderId, @Nullable String serversFolderId, Map<String, ServerState> servers) {
		public static final Data EMPTY = new Data(null, null, Map.of());
	}

	public static Data load() {
		if (!Files.isRegularFile(CACHE_FILE)) {
			return Data.EMPTY;
		}

		try (InputStream in = Files.newInputStream(CACHE_FILE)) {
			GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);
			String cloudifyFolderId = (String) json.get("cloudifyFolderId");
			String serversFolderId = (String) json.get("serversFolderId");

			Map<String, ServerState> servers = new LinkedHashMap<>();
			Object serversRaw = json.get("servers");
			if (serversRaw instanceof Map<?, ?> map) {
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (entry.getKey() instanceof String slug && entry.getValue() instanceof Map<?, ?> value) {
						String folderId = (String) value.get("folderId");
						Object manifestRaw = value.get("manifest");
						DriveTreeSync.DriveManifest manifest = manifestRaw instanceof Map<?, ?> manifestMap ? DriveTreeSync.fromJson(manifestMap) : DriveTreeSync.DriveManifest.empty();
						if (folderId != null) {
							servers.put(slug, new ServerState(folderId, manifest));
						}
					}
				}
			}

			return new Data(cloudifyFolderId, serversFolderId, servers);
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to read server Drive id cache, starting fresh", e);
			return Data.EMPTY;
		}
	}

	public static void save(Data data) {
		try {
			Files.createDirectories(CACHE_FILE.getParent());
			GenericJson json = new GenericJson();
			json.set("cloudifyFolderId", data.cloudifyFolderId());
			json.set("serversFolderId", data.serversFolderId());

			Map<String, GenericJson> serversJson = new LinkedHashMap<>();
			for (Map.Entry<String, ServerState> entry : data.servers().entrySet()) {
				ServerState state = entry.getValue();
				GenericJson serverJson = new GenericJson();
				serverJson.set("folderId", state.folderId());
				serverJson.set("manifest", DriveTreeSync.toJson(state.manifest()));
				serversJson.put(entry.getKey(), serverJson);
			}
			json.set("servers", serversJson);

			Files.write(CACHE_FILE, GoogleDriveAuth.JSON_FACTORY.toByteArray(json));
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to save server Drive id cache", e);
		}
	}
}
