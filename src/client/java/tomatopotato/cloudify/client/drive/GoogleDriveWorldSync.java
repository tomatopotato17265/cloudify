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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;

import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.DRIVE_FOLDER_MIME_TYPE;
import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.DRIVE_FOLDER_NAME;
import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.WORLDS_FOLDER_NAME;
import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.buildDriveClient;
import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.findFolder;
import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.findOrCreateFolder;
import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.uploadFile;

public class GoogleDriveWorldSync {
	private static final String METADATA_FILE_NAME = "metadata.json";
	private static final String ICON_FILE_NAME = "icon.png";

	public record WorldMetadata(String displayName, String gameMode, boolean hardcore, String version, long lastPlayed) {
	}

	public record DriveWorldEntry(
		String folderId,
		String name,
		String displayName,
		String gameMode,
		boolean hardcore,
		String version,
		long lastPlayedMillis,
		@Nullable String iconFileId
	) {
	}

	public static void uploadWorld(Path worldFolder, String worldName, WorldMetadata metadata, @Nullable Path iconFile) {
		try {
			if (!GoogleDriveLogin.isLoggedIn()) {
				return;
			}

			Credential credential = GoogleDriveLogin.getCredential();
			Drive drive = buildDriveClient(credential);

			String cloudifyFolderId = findOrCreateFolder(drive, DRIVE_FOLDER_NAME, null);
			String worldsFolderId = findOrCreateFolder(drive, WORLDS_FOLDER_NAME, cloudifyFolderId);
			String worldFolderId = findOrCreateFolder(drive, worldName, worldsFolderId);

			uploadDirectory(drive, worldFolder, worldFolderId);
			Cloudify.LOGGER.info("Uploaded world '{}' to Google Drive", worldName);

			try {
				uploadFile(drive, worldFolderId, METADATA_FILE_NAME, new ByteArrayContent("application/json", serializeMetadata(metadata)));
				Cloudify.LOGGER.info("Uploaded metadata for world '{}' to Google Drive", worldName);
				if (iconFile != null && Files.isRegularFile(iconFile)) {
					uploadFile(drive, worldFolderId, ICON_FILE_NAME, new FileContent("image/png", iconFile.toFile()));
					Cloudify.LOGGER.info("Uploaded icon for world '{}' to Google Drive ({})", worldName, iconFile);
				} else {
					Cloudify.LOGGER.info("No local icon.png found for world '{}', skipping icon upload (iconFile={})", worldName, iconFile);
				}
			} catch (IOException e) {
				Cloudify.LOGGER.warn("Failed to upload metadata/icon for world '{}' to Google Drive", worldName, e);
			}
		} catch (IOException e) {
			Cloudify.LOGGER.error("Failed to upload world '{}' to Google Drive", worldName, e);
		}
	}

	public static List<DriveWorldEntry> listWorlds() throws IOException {
		if (!GoogleDriveLogin.isLoggedIn()) {
			return List.of();
		}

		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = buildDriveClient(credential);

		Optional<String> cloudifyFolderId = findFolder(drive, DRIVE_FOLDER_NAME, null);
		if (cloudifyFolderId.isEmpty()) {
			return List.of();
		}

		Optional<String> worldsFolderId = findFolder(drive, WORLDS_FOLDER_NAME, cloudifyFolderId.get());
		if (worldsFolderId.isEmpty()) {
			return List.of();
		}

		String query = "'" + worldsFolderId.get() + "' in parents and mimeType = '" + DRIVE_FOLDER_MIME_TYPE + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id, name, modifiedTime)").setPageSize(1000).execute();
		List<File> worldFolders = result.getFiles();
		if (worldFolders == null) {
			return List.of();
		}

		List<DriveWorldEntry> entries = new ArrayList<>();
		for (File worldFolder : worldFolders) {
			entries.add(toDriveWorldEntry(drive, worldFolder));
		}
		return entries;
	}

	public static CompletableFuture<List<DriveWorldEntry>> listWorldsAsync() {
		return CompletableFuture.supplyAsync(GoogleDriveWorldSync::listWorldsUnchecked, Util.backgroundExecutor());
	}

	private static List<DriveWorldEntry> listWorldsUnchecked() {
		try {
			return listWorlds();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static byte[] downloadIcon(String fileId) throws IOException {
		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = buildDriveClient(credential);
		try (InputStream in = drive.files().get(fileId).executeMediaAsInputStream()) {
			return in.readAllBytes();
		}
	}

	public static CompletableFuture<byte[]> downloadIconAsync(String fileId) {
		return CompletableFuture.supplyAsync(() -> downloadIconUnchecked(fileId), Util.backgroundExecutor());
	}

	private static byte[] downloadIconUnchecked(String fileId) {
		try {
			return downloadIcon(fileId);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static CompletableFuture<Void> importWorldAsync(DriveWorldEntry entry, Path targetDir) {
		return CompletableFuture.runAsync(() -> importWorldUnchecked(entry, targetDir), Util.backgroundExecutor());
	}

	private static void importWorldUnchecked(DriveWorldEntry entry, Path targetDir) {
		try {
			importWorld(entry, targetDir);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void importWorld(DriveWorldEntry entry, Path targetDir) throws IOException {
		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = buildDriveClient(credential);
		downloadDirectory(drive, entry.folderId(), targetDir, true);
	}

	public static void deleteWorld(String worldName) throws IOException {
		if (!GoogleDriveLogin.isLoggedIn()) {
			return;
		}

		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = buildDriveClient(credential);

		Optional<String> cloudifyFolderId = findFolder(drive, DRIVE_FOLDER_NAME, null);
		if (cloudifyFolderId.isEmpty()) {
			return;
		}

		Optional<String> worldsFolderId = findFolder(drive, WORLDS_FOLDER_NAME, cloudifyFolderId.get());
		if (worldsFolderId.isEmpty()) {
			return;
		}

		Optional<String> worldFolderId = findFolder(drive, worldName, worldsFolderId.get());
		if (worldFolderId.isEmpty()) {
			return;
		}

		GoogleDriveFolders.trashRecursively(drive, worldFolderId.get());
		Cloudify.LOGGER.info("Trashed world '{}' in Google Drive", worldName);
	}

	public static CompletableFuture<Void> deleteWorldAsync(String worldName) {
		return CompletableFuture.runAsync(() -> deleteWorldUnchecked(worldName), Util.backgroundExecutor());
	}

	private static void deleteWorldUnchecked(String worldName) {
		try {
			deleteWorld(worldName);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void downloadDirectory(Drive drive, String folderId, Path targetDir, boolean isRoot) throws IOException {
		Files.createDirectories(targetDir);

		String query = "'" + folderId + "' in parents and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id, name, mimeType)").setPageSize(1000).execute();
		List<File> children = result.getFiles();
		if (children == null) {
			return;
		}

		for (File child : children) {
			String name = child.getName();
			if (isRoot && (name.equals(METADATA_FILE_NAME) || name.equals(ICON_FILE_NAME))) {
				continue;
			}

			if (DRIVE_FOLDER_MIME_TYPE.equals(child.getMimeType())) {
				downloadDirectory(drive, child.getId(), targetDir.resolve(name), false);
			} else {
				try (InputStream in = drive.files().get(child.getId()).executeMediaAsInputStream()) {
					Files.copy(in, targetDir.resolve(name));
				}
			}
		}
	}

	private static DriveWorldEntry toDriveWorldEntry(Drive drive, File worldFolder) throws IOException {
		String worldName = worldFolder.getName();
		String worldFolderId = worldFolder.getId();
		long fallbackLastPlayed = worldFolder.getModifiedTime() != null ? worldFolder.getModifiedTime().getValue() : 0L;

		String query = "'"
			+ worldFolderId
			+ "' in parents and trashed = false and (name = '"
			+ METADATA_FILE_NAME
			+ "' or name = '"
			+ ICON_FILE_NAME
			+ "')";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id, name)").execute();
		List<File> siblingFiles = result.getFiles();

		String metadataFileId = null;
		String iconFileId = null;
		if (siblingFiles != null) {
			for (File file : siblingFiles) {
				if (file.getName().equals(METADATA_FILE_NAME)) {
					metadataFileId = file.getId();
				} else if (file.getName().equals(ICON_FILE_NAME)) {
					iconFileId = file.getId();
				}
			}
		}

		WorldMetadata metadata = null;
		if (metadataFileId != null) {
			try {
				metadata = downloadMetadata(drive, metadataFileId);
			} catch (IOException e) {
				Cloudify.LOGGER.warn("Failed to read metadata for world '{}' from Google Drive", worldName, e);
			}
		} else {
			Cloudify.LOGGER.debug("No metadata sidecar found for world '{}' in Google Drive", worldName);
		}

		if (iconFileId != null) {
			Cloudify.LOGGER.info("Found icon sidecar (fileId={}) for world '{}' in Google Drive", iconFileId, worldName);
		} else {
			Cloudify.LOGGER.info("No icon sidecar found for world '{}' in Google Drive", worldName);
		}

		if (metadata != null) {
			return new DriveWorldEntry(
				worldFolderId, worldName, metadata.displayName(), metadata.gameMode(), metadata.hardcore(), metadata.version(), metadata.lastPlayed(), iconFileId
			);
		}

		return new DriveWorldEntry(worldFolderId, worldName, worldName, "", false, "", fallbackLastPlayed, iconFileId);
	}

	private static WorldMetadata downloadMetadata(Drive drive, String fileId) throws IOException {
		try (InputStream in = drive.files().get(fileId).executeMediaAsInputStream()) {
			GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);
			String displayName = (String) json.get("displayName");
			String gameMode = (String) json.get("gameMode");
			Boolean hardcore = (Boolean) json.get("hardcore");
			String version = (String) json.get("version");
			Number lastPlayed = (Number) json.get("lastPlayed");
			return new WorldMetadata(
				displayName != null ? displayName : "",
				gameMode != null ? gameMode : "",
				hardcore != null && hardcore,
				version != null ? version : "",
				lastPlayed != null ? lastPlayed.longValue() : 0L
			);
		}
	}

	private static byte[] serializeMetadata(WorldMetadata metadata) throws IOException {
		GenericJson json = new GenericJson();
		json.set("displayName", metadata.displayName());
		json.set("gameMode", metadata.gameMode());
		json.set("hardcore", metadata.hardcore());
		json.set("version", metadata.version());
		json.set("lastPlayed", metadata.lastPlayed());
		return GoogleDriveAuth.JSON_FACTORY.toByteArray(json);
	}

	private static void uploadDirectory(Drive drive, Path localDir, String parentFolderId) throws IOException {
		try (var children = Files.list(localDir)) {
			for (Path child : (Iterable<Path>) children::iterator) {
				String name = child.getFileName().toString();
				try {
					if (Files.isDirectory(child)) {
						String childFolderId = findOrCreateFolder(drive, name, parentFolderId);
						uploadDirectory(drive, child, childFolderId);
					} else {
						uploadFile(drive, parentFolderId, name, new FileContent("application/octet-stream", child.toFile()));
					}
				} catch (IOException e) {
					Cloudify.LOGGER.warn("Failed to upload '{}', skipping (it may have been a transient file rewritten by the game)", child, e);
				}
			}
		}
	}

}
