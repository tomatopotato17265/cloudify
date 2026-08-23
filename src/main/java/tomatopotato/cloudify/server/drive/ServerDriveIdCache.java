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
import tomatopotato.cloudify.drive.GoogleDriveAuth;

public class ServerDriveIdCache {
	private static final Path CACHE_FILE = GoogleDriveAuth.TOKENS_DIRECTORY.resolve("server").resolve("drive-cache.json");

	public record Data(@Nullable String cloudifyFolderId, @Nullable String serversFolderId, Map<String, String> serverFolderIds) {
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

			Map<String, String> serverFolderIds = new LinkedHashMap<>();
			Object serverFolderIdsRaw = json.get("serverFolderIds");
			if (serverFolderIdsRaw instanceof Map<?, ?> map) {
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (entry.getKey() instanceof String slug && entry.getValue() instanceof String folderId) {
						serverFolderIds.put(slug, folderId);
					}
				}
			}

			return new Data(cloudifyFolderId, serversFolderId, serverFolderIds);
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
			json.set("serverFolderIds", data.serverFolderIds());
			Files.write(CACHE_FILE, GoogleDriveAuth.JSON_FACTORY.toByteArray(json));
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to save server Drive id cache", e);
		}
	}
}
