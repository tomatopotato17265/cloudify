package tomatopotato.cloudify.client.drive;

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

public class DriveIdCache {
	private static final Path CACHE_FILE = GoogleDriveAuth.TOKENS_DIRECTORY.resolve("drive-cache.json");

	public record InstanceIds(@Nullable String folderId, @Nullable String manifestFileId, @Nullable String metadataFileId) {
		public static final InstanceIds EMPTY = new InstanceIds(null, null, null);
	}

	public record Data(@Nullable String cloudifyFolderId, @Nullable String instancesFolderId, Map<String, InstanceIds> instances) {
		public static final Data EMPTY = new Data(null, null, Map.of());
	}

	public static Data load() {
		if (!Files.isRegularFile(CACHE_FILE)) {
			return Data.EMPTY;
		}

		try (InputStream in = Files.newInputStream(CACHE_FILE)) {
			GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);
			String cloudifyFolderId = (String) json.get("cloudifyFolderId");
			String instancesFolderId = (String) json.get("instancesFolderId");

			Map<String, InstanceIds> instances = new LinkedHashMap<>();
			Object instancesRaw = json.get("instances");
			if (instancesRaw instanceof Map<?, ?> map) {
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (entry.getKey() instanceof String slug && entry.getValue() instanceof Map<?, ?> value) {
						instances.put(
							slug, new InstanceIds((String) value.get("folderId"), (String) value.get("manifestFileId"), (String) value.get("metadataFileId"))
						);
					}
				}
			}

			return new Data(cloudifyFolderId, instancesFolderId, instances);
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to read Drive id cache, starting fresh", e);
			return Data.EMPTY;
		}
	}

	public static void save(Data data) {
		try {
			Files.createDirectories(CACHE_FILE.getParent());
			GenericJson json = new GenericJson();
			json.set("cloudifyFolderId", data.cloudifyFolderId());
			json.set("instancesFolderId", data.instancesFolderId());

			Map<String, GenericJson> instancesJson = new LinkedHashMap<>();
			for (Map.Entry<String, InstanceIds> entry : data.instances().entrySet()) {
				InstanceIds ids = entry.getValue();
				GenericJson instanceJson = new GenericJson();
				instanceJson.set("folderId", ids.folderId());
				instanceJson.set("manifestFileId", ids.manifestFileId());
				instanceJson.set("metadataFileId", ids.metadataFileId());
				instancesJson.put(entry.getKey(), instanceJson);
			}
			json.set("instances", instancesJson);

			Files.write(CACHE_FILE, GoogleDriveAuth.JSON_FACTORY.toByteArray(json));
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to save Drive id cache", e);
		}
	}
}
