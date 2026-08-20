package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.json.GenericJson;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
	private static final String MANIFEST_FILE_NAME = "manifest.json";
	private static final String ICON_FILE_NAME = "icon.png";
	private static final int UPLOAD_CONCURRENCY = 6;

	private static final AtomicInteger BACKGROUND_THREAD_COUNTER = new AtomicInteger();
	private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
		Thread thread = new Thread(r, "cloudify-world-io-" + BACKGROUND_THREAD_COUNTER.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

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

	public static void uploadWorld(Path worldFolder, String worldName, WorldMetadata metadata) throws IOException {
		uploadWorld(worldFolder, worldName, metadata, TransferProgressListener.NO_OP, new AtomicBoolean(false));
	}

	public static void uploadWorld(Path worldFolder, String worldName, WorldMetadata metadata, TransferProgressListener listener, AtomicBoolean cancelled) throws IOException {
		Cloudify.LOGGER.info("World sync for '{}' starting on thread '{}'", worldName, Thread.currentThread().getName());
		if (!GoogleDriveLogin.isLoggedIn()) {
			Cloudify.LOGGER.info("World sync for '{}' skipped: not logged in to Google Drive", worldName);
			return;
		}

		GoogleDriveFolders.resetRequestCount();
		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = buildDriveClient(credential);

		String cloudifyFolderId = findOrCreateFolder(drive, DRIVE_FOLDER_NAME, null);
		String worldsFolderId = findOrCreateFolder(drive, WORLDS_FOLDER_NAME, cloudifyFolderId);
		String worldFolderId = findOrCreateFolder(drive, worldName, worldsFolderId);
		Cloudify.LOGGER.info("World sync for '{}' bootstrap resolved", worldName);

		if (cancelled.get()) {
			Cloudify.LOGGER.info("World sync for '{}' cancelled during bootstrap", worldName);
			return;
		}

		DriveTreeSync.DriveManifest previousManifest = downloadManifestIfPresent(drive, worldFolderId).orElse(DriveTreeSync.DriveManifest.empty());
		Cloudify.LOGGER.info("World sync for '{}' read previous manifest ({} entries)", worldName, previousManifest.entries().size());

		if (cancelled.get()) {
			Cloudify.LOGGER.info("World sync for '{}' cancelled after reading the manifest", worldName);
			return;
		}

		AtomicInteger uploadThreadCounter = new AtomicInteger();
		ExecutorService uploadPool = Executors.newFixedThreadPool(UPLOAD_CONCURRENCY, r -> {
			Thread thread = new Thread(r, "cloudify-world-upload-" + uploadThreadCounter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		DriveTreeSync.SyncResult result;
		try {
			result = DriveTreeSync.sync(drive, worldFolderId, worldFolder, previousManifest, WorldFileFilter::isExcluded, listener, cancelled, uploadPool);
		} finally {
			uploadPool.shutdown();
		}

		DriveTreeSync.DriveManifest updatedManifest = new DriveTreeSync.DriveManifest(result.entries(), result.folderIds());
		uploadFile(drive, worldFolderId, MANIFEST_FILE_NAME, new ByteArrayContent("application/json", DriveTreeSync.serializeManifest(updatedManifest)));

		Cloudify.LOGGER.info(
			"Synced world '{}' to Google Drive ({} files changed/added, {} deleted, {} unchanged, {} skipped, {} requests)",
			worldName, result.uploadedCount(), result.deletedCount(), result.unchangedCount(), result.skippedCount(), GoogleDriveFolders.getRequestCount()
		);

		if (cancelled.get()) {
			return;
		}

		try {
			uploadFile(drive, worldFolderId, METADATA_FILE_NAME, new ByteArrayContent("application/json", serializeMetadata(metadata)));
			Cloudify.LOGGER.info("Uploaded metadata for world '{}' to Google Drive", worldName);
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to upload metadata for world '{}' to Google Drive", worldName, e);
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
		return CompletableFuture.supplyAsync(GoogleDriveWorldSync::listWorldsUnchecked, BACKGROUND_EXECUTOR);
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
		return CompletableFuture.supplyAsync(() -> downloadIconUnchecked(fileId), BACKGROUND_EXECUTOR);
	}

	private static byte[] downloadIconUnchecked(String fileId) {
		try {
			return downloadIcon(fileId);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static CompletableFuture<Void> importWorldAsync(DriveWorldEntry entry, Path targetDir) {
		return importWorldAsync(entry, targetDir, TransferProgressListener.NO_OP, new AtomicBoolean(false));
	}

	public static CompletableFuture<Void> importWorldAsync(DriveWorldEntry entry, Path targetDir, TransferProgressListener listener, AtomicBoolean cancelled) {
		return CompletableFuture.runAsync(() -> importWorldUnchecked(entry, targetDir, listener, cancelled), BACKGROUND_EXECUTOR);
	}

	private static void importWorldUnchecked(DriveWorldEntry entry, Path targetDir, TransferProgressListener listener, AtomicBoolean cancelled) {
		try {
			importWorld(entry, targetDir, listener, cancelled);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void importWorld(DriveWorldEntry entry, Path targetDir, TransferProgressListener listener, AtomicBoolean cancelled) throws IOException {
		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = buildDriveClient(credential);
		DriveTreeSync.download(drive, entry.folderId(), targetDir, Set.of(METADATA_FILE_NAME, MANIFEST_FILE_NAME), 0L, 0, listener, cancelled);
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
		return CompletableFuture.runAsync(() -> deleteWorldUnchecked(worldName), BACKGROUND_EXECUTOR);
	}

	private static void deleteWorldUnchecked(String worldName) {
		try {
			deleteWorld(worldName);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
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

	private static Optional<DriveTreeSync.DriveManifest> downloadManifestIfPresent(Drive drive, String worldFolderId) throws IOException {
		String query = "'" + worldFolderId + "' in parents and name = '" + MANIFEST_FILE_NAME + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();
		if (matches == null || matches.isEmpty()) {
			return Optional.empty();
		}

		try (InputStream in = drive.files().get(matches.get(0).getId()).executeMediaAsInputStream()) {
			return Optional.of(DriveTreeSync.deserializeManifest(in));
		}
	}
}
