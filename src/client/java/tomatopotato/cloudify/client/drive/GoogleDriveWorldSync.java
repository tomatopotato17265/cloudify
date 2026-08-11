package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.GenericJson;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;

public class GoogleDriveWorldSync {
	private static final String DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	private static final String DRIVE_FOLDER_NAME = "Cloudify";
	private static final String ZIP_EXTENSION = ".zip";
	private static final String METADATA_EXTENSION = ".metadata.json";
	private static final String ICON_EXTENSION = ".icon.png";

	public record WorldMetadata(String displayName, String gameMode, boolean hardcore, String version, long lastPlayed) {
	}

	public record DriveWorldEntry(
		String fileId,
		String name,
		long sizeBytes,
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
			Drive drive = new Drive.Builder(GoogleDriveAuth.HTTP_TRANSPORT, GoogleDriveAuth.JSON_FACTORY, credential).setApplicationName("Cloudify").build();

			String folderId = findOrCreateFolder(drive);

			Path zipFile = zipWorldFolder(worldFolder, worldName);
			try {
				uploadFile(drive, folderId, worldName + ZIP_EXTENSION, new FileContent("application/zip", zipFile.toFile()));
				Cloudify.LOGGER.info("Uploaded world '{}' to Google Drive", worldName);
			} finally {
				Files.deleteIfExists(zipFile);
			}

			try {
				uploadFile(drive, folderId, worldName + METADATA_EXTENSION, new ByteArrayContent("application/json", serializeMetadata(metadata)));
				Cloudify.LOGGER.info("Uploaded metadata for world '{}' to Google Drive", worldName);
				if (iconFile != null && Files.isRegularFile(iconFile)) {
					uploadFile(drive, folderId, worldName + ICON_EXTENSION, new FileContent("image/png", iconFile.toFile()));
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
		Drive drive = new Drive.Builder(GoogleDriveAuth.HTTP_TRANSPORT, GoogleDriveAuth.JSON_FACTORY, credential).setApplicationName("Cloudify").build();

		Optional<String> folderId = findFolder(drive);
		if (folderId.isEmpty()) {
			return List.of();
		}

		String query = "'" + folderId.get() + "' in parents and trashed = false";
		FileList result = drive.files()
			.list()
			.setQ(query)
			.setSpaces("drive")
			.setFields("files(id, name, size, modifiedTime)")
			.setPageSize(1000)
			.execute();
		List<File> files = result.getFiles();
		if (files == null) {
			return List.of();
		}

		Map<String, WorldFiles> worldsByName = new HashMap<>();
		for (File file : files) {
			String fileName = file.getName();
			if (fileName.endsWith(METADATA_EXTENSION)) {
				worldsByName.computeIfAbsent(stripSuffix(fileName, METADATA_EXTENSION), key -> new WorldFiles()).metadataFileId = file.getId();
			} else if (fileName.endsWith(ICON_EXTENSION)) {
				worldsByName.computeIfAbsent(stripSuffix(fileName, ICON_EXTENSION), key -> new WorldFiles()).iconFileId = file.getId();
			} else if (fileName.endsWith(ZIP_EXTENSION)) {
				worldsByName.computeIfAbsent(stripSuffix(fileName, ZIP_EXTENSION), key -> new WorldFiles()).zipFile = file;
			}
		}

		List<DriveWorldEntry> entries = new ArrayList<>();
		for (Map.Entry<String, WorldFiles> entry : worldsByName.entrySet()) {
			WorldFiles worldFiles = entry.getValue();
			if (worldFiles.zipFile == null) {
				continue;
			}
			entries.add(toDriveWorldEntry(drive, entry.getKey(), worldFiles));
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
		Drive drive = new Drive.Builder(GoogleDriveAuth.HTTP_TRANSPORT, GoogleDriveAuth.JSON_FACTORY, credential).setApplicationName("Cloudify").build();
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

	private static final class WorldFiles {
		private @Nullable File zipFile;
		private @Nullable String metadataFileId;
		private @Nullable String iconFileId;
	}

	private static String stripSuffix(String name, String suffix) {
		return name.substring(0, name.length() - suffix.length());
	}

	private static DriveWorldEntry toDriveWorldEntry(Drive drive, String worldName, WorldFiles worldFiles) {
		File zipFile = worldFiles.zipFile;
		long sizeBytes = zipFile.getSize() != null ? zipFile.getSize() : 0L;
		long fallbackLastPlayed = zipFile.getModifiedTime() != null ? zipFile.getModifiedTime().getValue() : 0L;

		WorldMetadata metadata = null;
		if (worldFiles.metadataFileId != null) {
			try {
				metadata = downloadMetadata(drive, worldFiles.metadataFileId);
			} catch (IOException e) {
				Cloudify.LOGGER.warn("Failed to read metadata for world '{}' from Google Drive", worldName, e);
			}
		} else {
			Cloudify.LOGGER.debug("No metadata sidecar found for world '{}' in Google Drive", worldName);
		}

		if (worldFiles.iconFileId != null) {
			Cloudify.LOGGER.info("Found icon sidecar (fileId={}) for world '{}' in Google Drive", worldFiles.iconFileId, worldName);
		} else {
			Cloudify.LOGGER.info("No icon sidecar found for world '{}' in Google Drive", worldName);
		}

		if (metadata != null) {
			return new DriveWorldEntry(
				zipFile.getId(),
				worldName,
				sizeBytes,
				metadata.displayName(),
				metadata.gameMode(),
				metadata.hardcore(),
				metadata.version(),
				metadata.lastPlayed(),
				worldFiles.iconFileId
			);
		}

		return new DriveWorldEntry(zipFile.getId(), worldName, sizeBytes, worldName, "", false, "", fallbackLastPlayed, worldFiles.iconFileId);
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

	private static Path zipWorldFolder(Path worldFolder, String worldName) throws IOException {
		Path zipFile = Files.createTempFile("cloudify-" + worldName, ".zip");
		try (OutputStream fileOut = Files.newOutputStream(zipFile); ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
			try (var paths = Files.walk(worldFolder)) {
				for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
					zipOut.putNextEntry(new ZipEntry(worldFolder.relativize(path).toString().replace('\\', '/')));
					Files.copy(path, zipOut);
					zipOut.closeEntry();
				}
			}
		}
		return zipFile;
	}

	private static String findOrCreateFolder(Drive drive) throws IOException {
		Optional<String> existingFolderId = findFolder(drive);
		if (existingFolderId.isPresent()) {
			return existingFolderId.get();
		}

		File folderMetadata = new File().setName(DRIVE_FOLDER_NAME).setMimeType(DRIVE_FOLDER_MIME_TYPE);
		File folder = drive.files().create(folderMetadata).setFields("id").execute();
		return folder.getId();
	}

	private static Optional<String> findFolder(Drive drive) throws IOException {
		String query = "mimeType = '" + DRIVE_FOLDER_MIME_TYPE + "' and name = '" + DRIVE_FOLDER_NAME + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();
		if (matches == null || matches.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(matches.get(0).getId());
	}

	private static void uploadFile(Drive drive, String folderId, String fileName, AbstractInputStreamContent mediaContent) throws IOException {
		String query = "'" + folderId + "' in parents and name = '" + fileName + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();

		if (matches != null && !matches.isEmpty()) {
			drive.files().update(matches.get(0).getId(), new File(), mediaContent).execute();
		} else {
			File metadata = new File().setName(fileName).setParents(List.of(folderId));
			drive.files().create(metadata, mediaContent).execute();
		}
	}
}
