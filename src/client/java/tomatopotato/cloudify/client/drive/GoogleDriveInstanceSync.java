package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;

import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.DRIVE_FOLDER_NAME;
import static tomatopotato.cloudify.client.drive.GoogleDriveFolders.INSTANCES_FOLDER_NAME;

public class GoogleDriveInstanceSync {
	private static final String METADATA_FILE_NAME = "metadata.json";
	private static final String MANIFEST_FILE_NAME = "manifest.json";
	// I/O-bound, not CPU-bound, so this is independent of core count — see the pool creation site.
	private static final int UPLOAD_CONCURRENCY = 6;

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

	public record InstanceManifest(Map<String, FileFingerprint> entries, Map<String, String> folderIds) {
		public static InstanceManifest empty() {
			return new InstanceManifest(new LinkedHashMap<>(), new LinkedHashMap<>());
		}
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
		if (!GoogleDriveLogin.isLoggedIn()) {
			return;
		}

		long startedAt = System.nanoTime();
		GoogleDriveFolders.resetRequestCount();
		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = GoogleDriveFolders.buildDriveClient(credential);

		String slug = slugify(targetName);
		DriveIdCache.Data idCache = DriveIdCache.load();
		DriveIdCache.InstanceIds cachedIds = idCache.instances().getOrDefault(slug, DriveIdCache.InstanceIds.EMPTY);

		String cloudifyFolderId = idCache.cloudifyFolderId();
		String instancesFolderId = idCache.instancesFolderId();
		String instanceFolderId = cachedIds.folderId();
		boolean cacheUsable = cloudifyFolderId != null && instancesFolderId != null && instanceFolderId != null && isFolderUsable(drive, instanceFolderId);
		if (!cacheUsable) {
			cloudifyFolderId = GoogleDriveFolders.findOrCreateFolder(drive, DRIVE_FOLDER_NAME, null);
			instancesFolderId = GoogleDriveFolders.findOrCreateFolder(drive, INSTANCES_FOLDER_NAME, cloudifyFolderId);
			instanceFolderId = GoogleDriveFolders.findOrCreateFolder(drive, slug, instancesFolderId);
			cachedIds = DriveIdCache.InstanceIds.EMPTY;
		}
		long bootstrapDoneAt = System.nanoTime();

		InstanceManifest previousManifest = downloadManifestIfPresent(drive, instanceFolderId, cachedIds.manifestFileId()).orElse(InstanceManifest.empty());
		long manifestReadDoneAt = System.nanoTime();

		Map<String, FileFingerprint> localFingerprints = computeLocalFingerprints(gameDir, previousManifest);
		long walkHashDoneAt = System.nanoTime();

		Map<String, String> folderIdCache = new ConcurrentHashMap<>(previousManifest.folderIds());
		folderIdCache.put("", instanceFolderId);

		Map<String, FileFingerprint> updatedEntries = new ConcurrentHashMap<>();
		List<Map.Entry<String, FileFingerprint>> toUpload = new ArrayList<>();
		long totalSizeBytes = 0L;

		for (Map.Entry<String, FileFingerprint> entry : localFingerprints.entrySet()) {
			FileFingerprint local = entry.getValue();
			totalSizeBytes += local.sizeBytes();

			FileFingerprint previous = previousManifest.entries().get(entry.getKey());
			if (previous != null && previous.contentHash().equals(local.contentHash()) && previous.driveFileId() != null) {
				updatedEntries.put(entry.getKey(), previous);
			} else {
				toUpload.add(entry);
			}
		}

		Set<String> deletedPaths = new HashSet<>(previousManifest.entries().keySet());
		deletedPaths.removeAll(localFingerprints.keySet());

		long totalBytes = 0L;
		for (Map.Entry<String, FileFingerprint> entry : toUpload) {
			totalBytes += entry.getValue().sizeBytes();
		}

		final long totalUploadBytes = totalBytes;
		int totalFiles = toUpload.size() + deletedPaths.size();
		int unchangedFiles = updatedEntries.size();
		long diffDoneAt = System.nanoTime();

		List<String> pathsToUpload = new ArrayList<>();
		for (Map.Entry<String, FileFingerprint> entry : toUpload) {
			pathsToUpload.add(entry.getKey());
		}
		preResolveFolders(drive, pathsToUpload, folderIdCache);
		long folderPrePassDoneAt = System.nanoTime();

		AtomicLong bytesTransferred = new AtomicLong();
		AtomicInteger filesTransferred = new AtomicInteger();
		AtomicInteger skippedCount = new AtomicInteger();
		listener.onProgress(0, totalBytes, 0, totalFiles, "");

		AtomicInteger uploadThreadCounter = new AtomicInteger();
		ExecutorService uploadPool = Executors.newFixedThreadPool(UPLOAD_CONCURRENCY, r -> {
			Thread thread = new Thread(r, "cloudify-upload-" + uploadThreadCounter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		try {
			List<CompletableFuture<Void>> uploadFutures = new ArrayList<>();
			for (Map.Entry<String, FileFingerprint> entry : toUpload) {
				if (cancelled.get()) {
					break;
				}

				String relativePath = entry.getKey();
				FileFingerprint local = entry.getValue();
				uploadFutures.add(CompletableFuture.runAsync(() -> {
					if (cancelled.get()) {
						return;
					}

					FileFingerprint previous = previousManifest.entries().get(relativePath);
					try {
						Path relative = Path.of(relativePath);
						Path parent = relative.getParent();
						String folderId = parent == null ? folderIdCache.get("") : folderIdCache.get(toKey(parent));
						String fileId = GoogleDriveFolders.uploadFile(
							drive, folderId, relative.getFileName().toString(), new FileContent("application/octet-stream", gameDir.resolve(relativePath).toFile()), local.driveFileId()
						);
						updatedEntries.put(relativePath, new FileFingerprint(relativePath, local.sizeBytes(), local.mtimeMillis(), local.contentHash(), fileId));
					} catch (IOException e) {
						Cloudify.LOGGER.warn("Failed to sync '{}' to Google Drive, skipping (it may have been a transient file rewritten by the game)", relativePath, e);
						skippedCount.incrementAndGet();
						if (previous != null) {
							updatedEntries.put(relativePath, previous);
						}
					}

					long newBytesTransferred = bytesTransferred.addAndGet(local.sizeBytes());
					int newFilesTransferred = filesTransferred.incrementAndGet();
					listener.onProgress(newBytesTransferred, totalUploadBytes, newFilesTransferred, totalFiles, relativePath);
				}, uploadPool));
			}
			CompletableFuture.allOf(uploadFutures.toArray(CompletableFuture[]::new)).join();
		} finally {
			uploadPool.shutdown();
		}
		long uploadDoneAt = System.nanoTime();

		Set<String> unattemptedDeletes = new HashSet<>(deletedPaths);
		if (!cancelled.get()) {
			for (String relativePath : deletedPaths) {
				if (cancelled.get()) {
					break;
				}
				unattemptedDeletes.remove(relativePath);

				FileFingerprint gone = previousManifest.entries().get(relativePath);
				if (gone.driveFileId() == null) {
					Cloudify.LOGGER.warn("No Google Drive file id recorded for deleted local file '{}', leaving it in place on Drive", relativePath);
					skippedCount.incrementAndGet();
				} else {
					try {
						drive.files().update(gone.driveFileId(), new File().setTrashed(true)).execute();
					} catch (IOException e) {
						Cloudify.LOGGER.warn("Failed to trash deleted file '{}' on Google Drive, will retry next sync", relativePath, e);
						skippedCount.incrementAndGet();
						updatedEntries.put(relativePath, gone);
					}
				}

				int newFilesTransferred = filesTransferred.incrementAndGet();
				listener.onProgress(bytesTransferred.get(), totalBytes, newFilesTransferred, totalFiles, relativePath);
			}
		}
		long deleteDoneAt = System.nanoTime();

		for (Map.Entry<String, FileFingerprint> entry : toUpload) {
			String relativePath = entry.getKey();
			if (!updatedEntries.containsKey(relativePath)) {
				FileFingerprint previous = previousManifest.entries().get(relativePath);
				if (previous != null) {
					updatedEntries.put(relativePath, previous);
				}
			}
		}
		for (String relativePath : unattemptedDeletes) {
			updatedEntries.put(relativePath, previousManifest.entries().get(relativePath));
		}

		InstanceManifest updatedManifest = new InstanceManifest(updatedEntries, folderIdCache);
		String manifestFileId = GoogleDriveFolders.uploadFile(
			drive, instanceFolderId, MANIFEST_FILE_NAME, new ByteArrayContent("application/json", serializeManifest(updatedManifest)), cachedIds.manifestFileId()
		);

		if (cancelled.get()) {
			Map<String, DriveIdCache.InstanceIds> cancelledInstances = new LinkedHashMap<>(idCache.instances());
			cancelledInstances.put(slug, new DriveIdCache.InstanceIds(instanceFolderId, manifestFileId, cachedIds.metadataFileId()));
			DriveIdCache.save(new DriveIdCache.Data(cloudifyFolderId, instancesFolderId, cancelledInstances));

			Cloudify.LOGGER.info(
				"Instance sync for '{}' was cancelled after {} ms, {} requests ({} of {} files transferred, {} skipped); manifest updated with partial progress",
				targetName, millis(startedAt, deleteDoneAt), GoogleDriveFolders.getRequestCount(), filesTransferred.get(), totalFiles, skippedCount.get()
			);
			return;
		}

		InstanceMetadata updatedMetadata = new InstanceMetadata(
			metadata.displayName(),
			metadata.minecraftVersion(),
			metadata.fabricLoaderVersion(),
			metadata.mods(),
			System.currentTimeMillis(),
			totalSizeBytes,
			updatedEntries.size()
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
			toUpload.size(),
			deletedPaths.size(),
			unchangedFiles,
			skippedCount.get(),
			totalSizeBytes,
			GoogleDriveFolders.getRequestCount(),
			millis(startedAt, commitDoneAt),
			millis(startedAt, bootstrapDoneAt),
			millis(bootstrapDoneAt, manifestReadDoneAt),
			millis(manifestReadDoneAt, walkHashDoneAt),
			millis(walkHashDoneAt, diffDoneAt),
			millis(diffDoneAt, folderPrePassDoneAt),
			millis(folderPrePassDoneAt, uploadDoneAt),
			millis(uploadDoneAt, deleteDoneAt),
			millis(deleteDoneAt, commitDoneAt)
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
		return CompletableFuture.runAsync(() -> syncInstanceUnchecked(gameDir, targetName, metadata, listener, cancelled), Util.backgroundExecutor());
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
		return CompletableFuture.supplyAsync(GoogleDriveInstanceSync::listInstancesUnchecked, Util.backgroundExecutor());
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
		return CompletableFuture.runAsync(() -> downloadInstanceUnchecked(entry, targetDir, listener, cancelled), Util.backgroundExecutor());
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

		TransferState state = new TransferState();
		boolean completed = downloadDirectory(drive, entry.folderId(), targetDir, true, listener, cancelled, totalBytes, totalFiles, state);
		if (!completed) {
			Cloudify.LOGGER.info("Instance restore for '{}' was cancelled before completion", entry.displayName());
		}
	}

	private static final class TransferState {
		long bytesTransferred;
		int filesTransferred;
	}

	private static boolean downloadDirectory(
		Drive drive, String folderId, Path targetDir, boolean isRoot, TransferProgressListener listener, AtomicBoolean cancelled, long totalBytes, int totalFiles, TransferState state
	) throws IOException {
		Files.createDirectories(targetDir);

		String query = "'" + folderId + "' in parents and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id, name, mimeType, size)").setPageSize(1000).execute();
		List<File> children = result.getFiles();
		if (children == null) {
			return true;
		}

		for (File child : children) {
			if (cancelled.get()) {
				return false;
			}

			String name = child.getName();
			if (isRoot && (name.equals(METADATA_FILE_NAME) || name.equals(MANIFEST_FILE_NAME))) {
				continue;
			}

			if (GoogleDriveFolders.DRIVE_FOLDER_MIME_TYPE.equals(child.getMimeType())) {
				if (!downloadDirectory(drive, child.getId(), targetDir.resolve(name), false, listener, cancelled, totalBytes, totalFiles, state)) {
					return false;
				}
			} else {
				try (InputStream in = drive.files().get(child.getId()).executeMediaAsInputStream()) {
					Files.copy(in, targetDir.resolve(name));
				}
				state.bytesTransferred += child.getSize() != null ? child.getSize() : 0L;
				state.filesTransferred++;
				listener.onProgress(state.bytesTransferred, totalBytes, state.filesTransferred, totalFiles, name);
			}
		}
		return true;
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
		return CompletableFuture.runAsync(() -> deleteInstanceUnchecked(instanceFolderId), Util.backgroundExecutor());
	}

	private static void deleteInstanceUnchecked(String instanceFolderId) {
		try {
			deleteInstance(instanceFolderId);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void preResolveFolders(Drive drive, List<String> relativeFilePaths, Map<String, String> folderIdCache) throws IOException {
		Set<String> ancestorDirs = new TreeSet<>();
		for (String relativePath : relativeFilePaths) {
			Path parent = Path.of(relativePath).getParent();
			while (parent != null) {
				ancestorDirs.add(toKey(parent));
				parent = parent.getParent();
			}
		}

		for (String dirPath : ancestorDirs) {
			if (folderIdCache.containsKey(dirPath)) {
				continue;
			}

			Path dir = Path.of(dirPath);
			Path parentDir = dir.getParent();
			String parentId = parentDir == null ? folderIdCache.get("") : folderIdCache.get(toKey(parentDir));
			String folderId = GoogleDriveFolders.findOrCreateFolder(drive, dir.getFileName().toString(), parentId);
			folderIdCache.put(dirPath, folderId);
		}
	}

	private static String toKey(Path relativeDir) {
		return relativeDir.toString().replace(java.io.File.separatorChar, '/');
	}

	private static boolean isFolderUsable(Drive drive, String folderId) throws IOException {
		try {
			File file = drive.files().get(folderId).setFields("id,trashed").execute();
			return !Boolean.TRUE.equals(file.getTrashed());
		} catch (GoogleJsonResponseException e) {
			if (e.getStatusCode() == 404) {
				return false;
			}
			throw e;
		}
	}

	private static Optional<InstanceManifest> downloadManifestIfPresent(Drive drive, String instanceFolderId, @Nullable String knownManifestFileId) throws IOException {
		if (knownManifestFileId != null) {
			try (InputStream in = drive.files().get(knownManifestFileId).executeMediaAsInputStream()) {
				return Optional.of(deserializeManifest(in));
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

		List<String> sortedPaths = new ArrayList<>(manifest.entries().keySet());
		Collections.sort(sortedPaths);
		List<GenericJson> entriesJson = new ArrayList<>();
		for (String relativePath : sortedPaths) {
			FileFingerprint fingerprint = manifest.entries().get(relativePath);
			GenericJson entryJson = new GenericJson();
			entryJson.set("relativePath", fingerprint.relativePath());
			entryJson.set("sizeBytes", fingerprint.sizeBytes());
			entryJson.set("mtimeMillis", fingerprint.mtimeMillis());
			entryJson.set("contentHash", fingerprint.contentHash());
			entryJson.set("driveFileId", fingerprint.driveFileId());
			entriesJson.add(entryJson);
		}
		json.set("entries", entriesJson);
		json.set("folders", new TreeMap<>(manifest.folderIds()));

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

		Map<String, String> folderIds = new LinkedHashMap<>();
		Object foldersRaw = json.get("folders");
		if (foldersRaw instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() instanceof String path && entry.getValue() instanceof String driveFileId) {
					folderIds.put(path, driveFileId);
				}
			}
		}

		return new InstanceManifest(entries, folderIds);
	}
}
