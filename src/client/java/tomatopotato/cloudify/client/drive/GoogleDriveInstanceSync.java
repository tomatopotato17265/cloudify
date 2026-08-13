package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.GenericJson;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;

import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.DRIVE_FOLDER_NAME;
import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.INSTANCES_FOLDER_NAME;

public class GoogleDriveInstanceSync {
	private static final String METADATA_FILE_NAME = "metadata.json";
	private static final String MANIFEST_FILE_NAME = "manifest.json";

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

	public record FileFingerprint(String relativePath, long sizeBytes, long mtimeMillis, String contentHash, @Nullable String driveFileId) {
	}

	public record InstanceManifest(Map<String, FileFingerprint> entries) {
		public static InstanceManifest empty() {
			return new InstanceManifest(new LinkedHashMap<>());
		}
	}

	public static void syncInstance(Path gameDir, String targetName, InstanceMetadata metadata) throws IOException {
		if (!GoogleDriveLogin.isLoggedIn()) {
			return;
		}

		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = GoogleDriveFolders.buildDriveClient(credential);

		String cloudifyFolderId = GoogleDriveFolders.findOrCreateFolder(drive, DRIVE_FOLDER_NAME, null);
		String instancesFolderId = GoogleDriveFolders.findOrCreateFolder(drive, INSTANCES_FOLDER_NAME, cloudifyFolderId);
		String instanceFolderId = GoogleDriveFolders.findOrCreateFolder(drive, slugify(targetName), instancesFolderId);

		InstanceManifest previousManifest = downloadManifestIfPresent(drive, instanceFolderId).orElse(InstanceManifest.empty());
		Map<String, FileFingerprint> localFingerprints = computeLocalFingerprints(gameDir, previousManifest);

		Map<String, String> folderIdCache = new HashMap<>();
		folderIdCache.put("", instanceFolderId);

		Map<String, FileFingerprint> updatedEntries = new LinkedHashMap<>();
		long totalSizeBytes = 0L;

		for (Map.Entry<String, FileFingerprint> entry : localFingerprints.entrySet()) {
			String relativePath = entry.getKey();
			FileFingerprint local = entry.getValue();
			FileFingerprint previous = previousManifest.entries().get(relativePath);
			totalSizeBytes += local.sizeBytes();

			if (previous != null && previous.contentHash().equals(local.contentHash()) && previous.driveFileId() != null) {
				updatedEntries.put(relativePath, previous);
				continue;
			}

			try {
				Path relative = Path.of(relativePath);
				String folderId = resolveFolderId(drive, relative.getParent(), folderIdCache);
				String fileId = GoogleDriveFolders.uploadFile(
					drive, folderId, relative.getFileName().toString(), new FileContent("application/octet-stream", gameDir.resolve(relativePath).toFile())
				);
				updatedEntries.put(relativePath, new FileFingerprint(relativePath, local.sizeBytes(), local.mtimeMillis(), local.contentHash(), fileId));
			} catch (IOException e) {
				Cloudify.LOGGER.warn("Failed to sync '{}' to Google Drive, skipping (it may have been a transient file rewritten by the game)", relativePath, e);
				if (previous != null) {
					updatedEntries.put(relativePath, previous);
				}
			}
		}

		Set<String> deletedPaths = new HashSet<>(previousManifest.entries().keySet());
		deletedPaths.removeAll(localFingerprints.keySet());
		for (String relativePath : deletedPaths) {
			FileFingerprint gone = previousManifest.entries().get(relativePath);
			if (gone.driveFileId() == null) {
				Cloudify.LOGGER.warn("No Google Drive file id recorded for deleted local file '{}', leaving it in place on Drive", relativePath);
				continue;
			}
			try {
				drive.files().update(gone.driveFileId(), new File().setTrashed(true)).execute();
			} catch (IOException e) {
				Cloudify.LOGGER.warn("Failed to trash deleted file '{}' on Google Drive, will retry next sync", relativePath, e);
				updatedEntries.put(relativePath, gone);
			}
		}

		InstanceManifest updatedManifest = new InstanceManifest(updatedEntries);
		InstanceMetadata updatedMetadata = new InstanceMetadata(
			metadata.displayName(),
			metadata.minecraftVersion(),
			metadata.fabricLoaderVersion(),
			metadata.mods(),
			System.currentTimeMillis(),
			totalSizeBytes,
			updatedEntries.size()
		);

		GoogleDriveFolders.uploadFile(drive, instanceFolderId, MANIFEST_FILE_NAME, new ByteArrayContent("application/json", serializeManifest(updatedManifest)));
		GoogleDriveFolders.uploadFile(drive, instanceFolderId, METADATA_FILE_NAME, new ByteArrayContent("application/json", serializeMetadata(updatedMetadata)));

		Cloudify.LOGGER.info("Synced instance '{}' to Google Drive ({} files changed/added, {} deleted)", targetName, localFingerprints.size(), deletedPaths.size());
	}

	public static CompletableFuture<Void> syncInstanceAsync(Path gameDir, String targetName, InstanceMetadata metadata) {
		return CompletableFuture.runAsync(() -> syncInstanceUnchecked(gameDir, targetName, metadata), Util.backgroundExecutor());
	}

	private static void syncInstanceUnchecked(Path gameDir, String targetName, InstanceMetadata metadata) {
		try {
			syncInstance(gameDir, targetName, metadata);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String resolveFolderId(Drive drive, @Nullable Path relativeDir, Map<String, String> folderIdCache) throws IOException {
		if (relativeDir == null) {
			return folderIdCache.get("");
		}

		String key = relativeDir.toString().replace(java.io.File.separatorChar, '/');
		String cached = folderIdCache.get(key);
		if (cached != null) {
			return cached;
		}

		String parentId = resolveFolderId(drive, relativeDir.getParent(), folderIdCache);
		String folderId = GoogleDriveFolders.findOrCreateFolder(drive, relativeDir.getFileName().toString(), parentId);
		folderIdCache.put(key, folderId);
		return folderId;
	}

	private static Optional<InstanceManifest> downloadManifestIfPresent(Drive drive, String instanceFolderId) throws IOException {
		String query = "'" + instanceFolderId + "' in parents and name = '" + MANIFEST_FILE_NAME + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();
		if (matches == null || matches.isEmpty()) {
			return Optional.empty();
		}

		try (InputStream in = drive.files().get(matches.get(0).getId()).executeMediaAsInputStream()) {
			return Optional.of(deserializeManifest(in));
		}
	}

	private static Map<String, FileFingerprint> computeLocalFingerprints(Path gameDir, InstanceManifest previousManifest) throws IOException {
		Map<String, FileFingerprint> result = new LinkedHashMap<>();
		Path normalizedGameDir = gameDir.toAbsolutePath().normalize();

		Files.walkFileTree(normalizedGameDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(normalizedGameDir) && InstanceFileFilter.isExcluded(normalizedGameDir, dir)) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (InstanceFileFilter.isExcluded(normalizedGameDir, file)) {
					return FileVisitResult.CONTINUE;
				}

				String relativePath = normalizedGameDir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
				long size = attrs.size();
				long mtime = attrs.lastModifiedTime().toMillis();
				FileFingerprint previous = previousManifest.entries().get(relativePath);

				String contentHash;
				if (previous != null && previous.sizeBytes() == size && previous.mtimeMillis() == mtime) {
					contentHash = previous.contentHash();
				} else {
					try {
						contentHash = hashFile(file);
					} catch (IOException e) {
						Cloudify.LOGGER.warn("Failed to hash '{}', skipping (it may have been a transient file rewritten by the game)", file, e);
						return FileVisitResult.CONTINUE;
					}
				}

				result.put(relativePath, new FileFingerprint(relativePath, size, mtime, contentHash, previous != null ? previous.driveFileId() : null));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				Cloudify.LOGGER.warn("Failed to visit '{}', skipping", file, exc);
				return FileVisitResult.CONTINUE;
			}
		});

		return result;
	}

	private static String hashFile(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = in.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	private static String slugify(String name) {
		String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
		slug = slug.replaceAll("^-+", "").replaceAll("-+$", "");
		return slug.isEmpty() ? "instance" : slug;
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
			entryJson.set("driveFileId", fingerprint.driveFileId());
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
					String driveFileId = (String) map.get("driveFileId");
					if (relativePath != null) {
						entries.put(
							relativePath,
							new FileFingerprint(
								relativePath,
								sizeBytes != null ? sizeBytes.longValue() : 0L,
								mtimeMillis != null ? mtimeMillis.longValue() : 0L,
								contentHash != null ? contentHash : "",
								driveFileId
							)
						);
					}
				}
			}
		}
		return new InstanceManifest(entries);
	}
}
