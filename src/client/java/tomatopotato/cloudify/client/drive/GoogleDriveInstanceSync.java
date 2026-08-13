package tomatopotato.cloudify.client.drive;

import com.google.api.client.json.GenericJson;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GoogleDriveInstanceSync {
	public record ModEntry(String id, String version) {
	}

	public record InstanceMetadata(
		String displayName,
		String minecraftVersion,
		String fabricLoaderVersion,
		List<ModEntry> mods,
		long lastSyncedMillis,
		long totalSizeBytes,
		int fileCount
	) {
	}

	public record FileFingerprint(String relativePath, long sizeBytes, long mtimeMillis, String contentHash) {
	}

	public record InstanceManifest(Map<String, FileFingerprint> entries) {
		public static InstanceManifest empty() {
			return new InstanceManifest(new LinkedHashMap<>());
		}
	}

	static byte[] serializeMetadata(InstanceMetadata metadata) throws IOException {
		GenericJson json = new GenericJson();
		json.set("displayName", metadata.displayName());
		json.set("minecraftVersion", metadata.minecraftVersion());
		json.set("fabricLoaderVersion", metadata.fabricLoaderVersion());

		List<GenericJson> modsJson = new ArrayList<>();
		for (ModEntry mod : metadata.mods()) {
			GenericJson modJson = new GenericJson();
			modJson.set("id", mod.id());
			modJson.set("version", mod.version());
			modsJson.add(modJson);
		}
		json.set("mods", modsJson);

		json.set("lastSyncedMillis", metadata.lastSyncedMillis());
		json.set("totalSizeBytes", metadata.totalSizeBytes());
		json.set("fileCount", metadata.fileCount());
		return GoogleDriveAuth.JSON_FACTORY.toByteArray(json);
	}

	static InstanceMetadata deserializeMetadata(InputStream in) throws IOException {
		GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);

		String displayName = (String) json.get("displayName");
		String minecraftVersion = (String) json.get("minecraftVersion");
		String fabricLoaderVersion = (String) json.get("fabricLoaderVersion");

		List<ModEntry> mods = new ArrayList<>();
		Object modsRaw = json.get("mods");
		if (modsRaw instanceof List<?> list) {
			for (Object entry : list) {
				if (entry instanceof Map<?, ?> map) {
					mods.add(new ModEntry((String) map.get("id"), (String) map.get("version")));
				}
			}
		}

		Number lastSyncedMillis = (Number) json.get("lastSyncedMillis");
		Number totalSizeBytes = (Number) json.get("totalSizeBytes");
		Number fileCount = (Number) json.get("fileCount");

		return new InstanceMetadata(
			displayName != null ? displayName : "",
			minecraftVersion != null ? minecraftVersion : "",
			fabricLoaderVersion != null ? fabricLoaderVersion : "",
			mods,
			lastSyncedMillis != null ? lastSyncedMillis.longValue() : 0L,
			totalSizeBytes != null ? totalSizeBytes.longValue() : 0L,
			fileCount != null ? fileCount.intValue() : 0
		);
	}

	static byte[] serializeManifest(InstanceManifest manifest) throws IOException {
		GenericJson json = new GenericJson();

		List<GenericJson> entriesJson = new ArrayList<>();
		for (FileFingerprint fingerprint : manifest.entries().values()) {
			GenericJson entryJson = new GenericJson();
			entryJson.set("relativePath", fingerprint.relativePath());
			entryJson.set("sizeBytes", fingerprint.sizeBytes());
			entryJson.set("mtimeMillis", fingerprint.mtimeMillis());
			entryJson.set("contentHash", fingerprint.contentHash());
			entriesJson.add(entryJson);
		}
		json.set("entries", entriesJson);
		return GoogleDriveAuth.JSON_FACTORY.toByteArray(json);
	}

	static InstanceManifest deserializeManifest(InputStream in) throws IOException {
		GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);

		Map<String, FileFingerprint> entries = new LinkedHashMap<>();
		Object entriesRaw = json.get("entries");
		if (entriesRaw instanceof List<?> list) {
			for (Object entry : list) {
				if (entry instanceof Map<?, ?> map) {
					String relativePath = (String) map.get("relativePath");
					Number sizeBytes = (Number) map.get("sizeBytes");
					Number mtimeMillis = (Number) map.get("mtimeMillis");
					String contentHash = (String) map.get("contentHash");
					if (relativePath != null) {
						entries.put(
							relativePath,
							new FileFingerprint(
								relativePath, sizeBytes != null ? sizeBytes.longValue() : 0L, mtimeMillis != null ? mtimeMillis.longValue() : 0L, contentHash != null ? contentHash : ""
							)
						);
					}
				}
			}
		}
		return new InstanceManifest(entries);
	}
}
