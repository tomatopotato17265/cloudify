package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.drive.DriveTreeSync;
import tomatopotato.cloudify.drive.GoogleDriveAuth;
import tomatopotato.cloudify.drive.GoogleDriveFolders;
import tomatopotato.cloudify.drive.TransferProgressListener;

import static tomatopotato.cloudify.drive.GoogleDriveFolders.DRIVE_FOLDER_NAME;
import static tomatopotato.cloudify.drive.GoogleDriveFolders.INSTANCES_FOLDER_NAME;

public class GoogleDriveInstanceSync {
	private static final String METADATA_FILE_NAME = "metadata.json";
	private static final String MANIFEST_FILE_NAME = "manifest.json";
	private static final int UPLOAD_CONCURRENCY = 6;

	private static final AtomicInteger BACKGROUND_THREAD_COUNTER = new AtomicInteger();
	private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
		Thread thread = new Thread(r, "cloudify-instance-io-" + BACKGROUND_THREAD_COUNTER.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

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

	public record DriveInstanceEntry(
		String folderId,
		String folderName,
		String displayName,
		String minecraftVersion,
		String fabricLoaderVersion,
		int modCount,
		long lastSyncedMillis,
		long totalSizeBytes,
		int fileCount
	) {
	}

	public static void syncInstance(Path gameDir, String targetName, InstanceMetadata metadata) throws IOException {
		syncInstance(gameDir, targetName, metadata, TransferProgressListener.NO_OP, new AtomicBoolean(false));
	}

	public static void syncInstance(Path gameDir, String targetName, InstanceMetadata metadata, TransferProgressListener listener, AtomicBoolean cancelled) throws IOException {
		Cloudify.LOGGER.info("Instance sync for '{}' starting on thread '{}'", targetName, Thread.currentThread().getName());
		if (!GoogleDriveLogin.isLoggedIn()) {
			Cloudify.LOGGER.info("Instance sync for '{}' skipped: not logged in to Google Drive", targetName);
			return;
		}

		long startedAt = System.nanoTime();
		GoogleDriveFolders.resetRequestCount();
		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = GoogleDriveFolders.buildDriveClient(credential);

		String slug = GoogleDriveFolders.slugify(targetName, "instance");
		DriveIdCache.Data idCache = DriveIdCache.load();
		DriveIdCache.InstanceIds cachedIds = idCache.instances().getOrDefault(slug, DriveIdCache.InstanceIds.EMPTY);

		String cloudifyFolderId = idCache.cloudifyFolderId();
		String instancesFolderId = idCache.instancesFolderId();
		String instanceFolderId = cachedIds.folderId();
		boolean cacheUsable = cloudifyFolderId != null && instancesFolderId != null && instanceFolderId != null && GoogleDriveFolders.isFolderUsable(drive, instanceFolderId);
		if (!cacheUsable) {
			cloudifyFolderId = GoogleDriveFolders.findOrCreateFolder(drive, DRIVE_FOLDER_NAME, null);
			instancesFolderId = GoogleDriveFolders.findOrCreateFolder(drive, INSTANCES_FOLDER_NAME, cloudifyFolderId);
			instanceFolderId = GoogleDriveFolders.findOrCreateFolder(drive, slug, instancesFolderId);
			cachedIds = DriveIdCache.InstanceIds.EMPTY;
		}
		long bootstrapDoneAt = System.nanoTime();
		Cloudify.LOGGER.info("Instance sync for '{}' bootstrap resolved (cache usable: {}) in {} ms", targetName, cacheUsable, millis(startedAt, bootstrapDoneAt));

		if (cancelled.get()) {
			Cloudify.LOGGER.info("Instance sync for '{}' cancelled during bootstrap", targetName);
			return;
		}

		DriveTreeSync.DriveManifest previousManifest = downloadManifestIfPresent(drive, instanceFolderId, cachedIds.manifestFileId()).orElse(DriveTreeSync.DriveManifest.empty());
		long manifestReadDoneAt = System.nanoTime();
		Cloudify.LOGGER.info(
			"Instance sync for '{}' read previous manifest ({} entries) in {} ms", targetName, previousManifest.entries().size(), millis(bootstrapDoneAt, manifestReadDoneAt)
		);

		if (cancelled.get()) {
			Cloudify.LOGGER.info("Instance sync for '{}' cancelled after reading the manifest", targetName);
			return;
		}

		AtomicInteger uploadThreadCounter = new AtomicInteger();
		ExecutorService uploadPool = Executors.newFixedThreadPool(UPLOAD_CONCURRENCY, r -> {
			Thread thread = new Thread(r, "cloudify-upload-" + uploadThreadCounter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		DriveTreeSync.SyncResult result;
		try {
			result = DriveTreeSync.sync(drive, instanceFolderId, gameDir, previousManifest, InstanceFileFilter::isExcluded, listener, cancelled, uploadPool, DriveTreeSync.UploadMode.UPDATE_IN_PLACE);
		} finally {
			uploadPool.shutdown();
		}
		long syncDoneAt = System.nanoTime();
		Cloudify.LOGGER.info("Instance sync for '{}' tree sync finished in {} ms", targetName, millis(manifestReadDoneAt, syncDoneAt));

		DriveTreeSync.DriveManifest updatedManifest = new DriveTreeSync.DriveManifest(result.entries(), result.folderIds());
		String manifestFileId = GoogleDriveFolders.uploadFile(
			drive, instanceFolderId, MANIFEST_FILE_NAME, new ByteArrayContent("application/json", DriveTreeSync.serializeManifest(updatedManifest)), cachedIds.manifestFileId()
		);

		if (cancelled.get()) {
			Map<String, DriveIdCache.InstanceIds> cancelledInstances = new LinkedHashMap<>(idCache.instances());
			cancelledInstances.put(slug, new DriveIdCache.InstanceIds(instanceFolderId, manifestFileId, cachedIds.metadataFileId()));
			DriveIdCache.save(new DriveIdCache.Data(cloudifyFolderId, instancesFolderId, cancelledInstances));

			Cloudify.LOGGER.info(
				"Instance sync for '{}' was cancelled after {} ms, {} requests ({} of {} files transferred, {} skipped); manifest updated with partial progress",
				targetName, millis(startedAt, syncDoneAt), GoogleDriveFolders.getRequestCount(), result.filesTransferredCount(), result.uploadedCount() + result.deletedCount(), result.skippedCount()
			);
			return;
		}

		InstanceMetadata updatedMetadata = new InstanceMetadata(
			metadata.displayName(),
			metadata.minecraftVersion(),
			metadata.fabricLoaderVersion(),
			metadata.mods(),
			System.currentTimeMillis(),
			result.totalLocalBytes(),
			result.entries().size()
		);
		String metadataFileId = GoogleDriveFolders.uploadFile(
			drive, instanceFolderId, METADATA_FILE_NAME, new ByteArrayContent("application/json", serializeMetadata(updatedMetadata)), cachedIds.metadataFileId()
		);
		long commitDoneAt = System.nanoTime();

		Map<String, DriveIdCache.InstanceIds> updatedInstances = new LinkedHashMap<>(idCache.instances());
		updatedInstances.put(slug, new DriveIdCache.InstanceIds(instanceFolderId, manifestFileId, metadataFileId));
		DriveIdCache.save(new DriveIdCache.Data(cloudifyFolderId, instancesFolderId, updatedInstances));

		Cloudify.LOGGER.info(
			"Synced instance '{}' to Google Drive ({} files changed/added, {} deleted, {} unchanged, {} skipped, {} bytes, {} requests) in {} ms"
				+ " [bootstrap {} / manifest {} / walk+hash {} / diff {} / folders {} / upload {} / delete {} / commit {}]",
			targetName,
			result.uploadedCount(),
			result.deletedCount(),
			result.unchangedCount(),
			result.skippedCount(),
			result.totalLocalBytes(),
			GoogleDriveFolders.getRequestCount(),
			millis(startedAt, commitDoneAt),
			millis(startedAt, bootstrapDoneAt),
			millis(bootstrapDoneAt, manifestReadDoneAt),
			result.walkHashMillis(),
			result.diffMillis(),
			result.folderPrePassMillis(),
			result.uploadMillis(),
			result.deleteMillis(),
			millis(syncDoneAt, commitDoneAt)
		);
	}

	private static long millis(long fromNanos, long toNanos) {
		return (toNanos - fromNanos) / 1_000_000L;
	}

	public static CompletableFuture<Void> syncInstanceAsync(Path gameDir, String targetName, InstanceMetadata metadata) {
		return syncInstanceAsync(gameDir, targetName, metadata, TransferProgressListener.NO_OP, new AtomicBoolean(false));
	}

	public static CompletableFuture<Void> syncInstanceAsync(
		Path gameDir, String targetName, InstanceMetadata metadata, TransferProgressListener listener, AtomicBoolean cancelled
	) {
		return CompletableFuture.runAsync(() -> syncInstanceUnchecked(gameDir, targetName, metadata, listener, cancelled), BACKGROUND_EXECUTOR);
	}

	private static void syncInstanceUnchecked(Path gameDir, String targetName, InstanceMetadata metadata, TransferProgressListener listener, AtomicBoolean cancelled) {
		try {
			syncInstance(gameDir, targetName, metadata, listener, cancelled);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static List<DriveInstanceEntry> listInstances() throws IOException {
		if (!GoogleDriveLogin.isLoggedIn()) {
			return List.of();
		}

		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = GoogleDriveFolders.buildDriveClient(credential);

		Optional<String> cloudifyFolderId = GoogleDriveFolders.findFolder(drive, DRIVE_FOLDER_NAME, null);
		if (cloudifyFolderId.isEmpty()) {
			return List.of();
		}

		Optional<String> instancesFolderId = GoogleDriveFolders.findFolder(drive, INSTANCES_FOLDER_NAME, cloudifyFolderId.get());
		if (instancesFolderId.isEmpty()) {
			return List.of();
		}

		String query = "'" + instancesFolderId.get() + "' in parents and mimeType = '" + GoogleDriveFolders.DRIVE_FOLDER_MIME_TYPE + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id, name, modifiedTime)").setPageSize(1000).execute();
		List<File> instanceFolders = result.getFiles();
		if (instanceFolders == null) {
			return List.of();
		}

		List<DriveInstanceEntry> entries = new ArrayList<>();
		for (File instanceFolder : instanceFolders) {
			entries.add(toDriveInstanceEntry(drive, instanceFolder));
		}
		return entries;
	}

	public static CompletableFuture<List<DriveInstanceEntry>> listInstancesAsync() {
		return CompletableFuture.supplyAsync(GoogleDriveInstanceSync::listInstancesUnchecked, BACKGROUND_EXECUTOR);
	}

	private static List<DriveInstanceEntry> listInstancesUnchecked() {
		try {
			return listInstances();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static DriveInstanceEntry toDriveInstanceEntry(Drive drive, File instanceFolder) throws IOException {
		String folderName = instanceFolder.getName();
		String folderId = instanceFolder.getId();
		long fallbackLastSynced = instanceFolder.getModifiedTime() != null ? instanceFolder.getModifiedTime().getValue() : 0L;

		Optional<InstanceMetadata> metadata;
		try {
			metadata = downloadMetadataIfPresent(drive, folderId);
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to read metadata for instance '{}' from Google Drive", folderName, e);
			metadata = Optional.empty();
		}

		if (metadata.isPresent()) {
			InstanceMetadata m = metadata.get();
			return new DriveInstanceEntry(
				folderId, folderName, m.displayName(), m.minecraftVersion(), m.fabricLoaderVersion(), m.mods().size(), m.lastSyncedMillis(), m.totalSizeBytes(), m.fileCount()
			);
		}

		return new DriveInstanceEntry(folderId, folderName, folderName, "", "", 0, fallbackLastSynced, 0L, 0);
	}

	private static Optional<InstanceMetadata> downloadMetadataIfPresent(Drive drive, String instanceFolderId) throws IOException {
		String query = "'" + instanceFolderId + "' in parents and name = '" + METADATA_FILE_NAME + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();
		if (matches == null || matches.isEmpty()) {
			return Optional.empty();
		}

		try (InputStream in = drive.files().get(matches.get(0).getId()).executeMediaAsInputStream()) {
			return Optional.of(deserializeMetadata(in));
		}
	}

	public static CompletableFuture<Void> downloadInstanceAsync(DriveInstanceEntry entry, Path targetDir) {
		return downloadInstanceAsync(entry, targetDir, TransferProgressListener.NO_OP, new AtomicBoolean(false));
	}

	public static CompletableFuture<Void> downloadInstanceAsync(DriveInstanceEntry entry, Path targetDir, TransferProgressListener listener, AtomicBoolean cancelled) {
		return CompletableFuture.runAsync(() -> downloadInstanceUnchecked(entry, targetDir, listener, cancelled), BACKGROUND_EXECUTOR);
	}

	private static void downloadInstanceUnchecked(DriveInstanceEntry entry, Path targetDir, TransferProgressListener listener, AtomicBoolean cancelled) {
		try {
			downloadInstance(entry, targetDir, listener, cancelled);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void downloadInstance(DriveInstanceEntry entry, Path targetDir, TransferProgressListener listener, AtomicBoolean cancelled) throws IOException {
		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = GoogleDriveFolders.buildDriveClient(credential);

		long totalBytes = entry.totalSizeBytes();
		int totalFiles = entry.fileCount();
		listener.onProgress(0, totalBytes, 0, totalFiles, "");

		boolean completed = DriveTreeSync.download(
			drive, entry.folderId(), targetDir, Set.of(METADATA_FILE_NAME, MANIFEST_FILE_NAME), totalBytes, totalFiles, listener, cancelled
		);
		if (!completed) {
			Cloudify.LOGGER.info("Instance restore for '{}' was cancelled before completion", entry.displayName());
		}
	}

	public static void deleteInstance(String instanceFolderId) throws IOException {
		if (!GoogleDriveLogin.isLoggedIn()) {
			return;
		}

		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = GoogleDriveFolders.buildDriveClient(credential);
		GoogleDriveFolders.trashRecursively(drive, instanceFolderId);
		Cloudify.LOGGER.info("Trashed instance backup (folderId={}) in Google Drive", instanceFolderId);
	}

	public static CompletableFuture<Void> deleteInstanceAsync(String instanceFolderId) {
		return CompletableFuture.runAsync(() -> deleteInstanceUnchecked(instanceFolderId), BACKGROUND_EXECUTOR);
	}

	private static void deleteInstanceUnchecked(String instanceFolderId) {
		try {
			deleteInstance(instanceFolderId);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Optional<DriveTreeSync.DriveManifest> downloadManifestIfPresent(Drive drive, String instanceFolderId, @Nullable String knownManifestFileId) throws IOException {
		if (knownManifestFileId != null) {
			try (InputStream in = drive.files().get(knownManifestFileId).executeMediaAsInputStream()) {
				return Optional.of(DriveTreeSync.deserializeManifest(in));
			} catch (GoogleJsonResponseException e) {
				if (e.getStatusCode() != 404) {
					throw e;
				}
			}
		}

		String query = "'" + instanceFolderId + "' in parents and name = '" + MANIFEST_FILE_NAME + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();
		if (matches == null || matches.isEmpty()) {
			return Optional.empty();
		}

		try (InputStream in = drive.files().get(matches.get(0).getId()).executeMediaAsInputStream()) {
			return Optional.of(DriveTreeSync.deserializeManifest(in));
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
}
