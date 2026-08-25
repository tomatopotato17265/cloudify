package tomatopotato.cloudify.drive;

import com.google.api.client.http.FileContent;
import com.google.api.client.json.GenericJson;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;

public class DriveTreeSync {
	public enum UploadMode {
		UPDATE_IN_PLACE,
		CREATE_ONLY
	}

	public record FileFingerprint(String relativePath, long sizeBytes, long mtimeMillis, String contentHash, @Nullable String driveFileId) {
	}

	public record DriveManifest(Map<String, FileFingerprint> entries, Map<String, String> folderIds) {
		public static DriveManifest empty() {
			return new DriveManifest(new LinkedHashMap<>(), new LinkedHashMap<>());
		}
	}

	public record SyncResult(
		Map<String, FileFingerprint> entries,
		Map<String, String> folderIds,
		int uploadedCount,
		int deletedCount,
		int unchangedCount,
		int skippedCount,
		int filesTransferredCount,
		long totalLocalBytes,
		long walkHashMillis,
		long diffMillis,
		long folderPrePassMillis,
		long uploadMillis,
		long deleteMillis
	) {
	}

	public static SyncResult sync(
		Drive drive,
		String rootFolderId,
		Path localRoot,
		DriveManifest previousManifest,
		BiPredicate<Path, Path> exclusionFilter,
		TransferProgressListener listener,
		AtomicBoolean cancelled,
		ExecutorService pool,
		UploadMode uploadMode
	) throws IOException {
		long walkHashStartedAt = System.nanoTime();
		Map<String, FileFingerprint> localFingerprints = computeLocalFingerprints(localRoot, previousManifest, exclusionFilter);
		long walkHashDoneAt = System.nanoTime();

		long totalLocalBytes = 0L;
		for (FileFingerprint local : localFingerprints.values()) {
			totalLocalBytes += local.sizeBytes();
		}

		if (cancelled.get()) {
			return unchangedResult(previousManifest, totalLocalBytes, millis(walkHashStartedAt, walkHashDoneAt), 0, 0);
		}

		Map<String, String> folderIdCache = uploadMode == UploadMode.UPDATE_IN_PLACE ? new ConcurrentHashMap<>(previousManifest.folderIds()) : new ConcurrentHashMap<>();
		folderIdCache.put("", rootFolderId);

		Map<String, FileFingerprint> updatedEntries = new ConcurrentHashMap<>();
		List<Map.Entry<String, FileFingerprint>> toUpload = new ArrayList<>();

		for (Map.Entry<String, FileFingerprint> entry : localFingerprints.entrySet()) {
			FileFingerprint local = entry.getValue();
			FileFingerprint previous = previousManifest.entries().get(entry.getKey());
			if (previous != null && previous.contentHash().equals(local.contentHash()) && previous.driveFileId() != null) {
				updatedEntries.put(entry.getKey(), previous);
			} else {
				toUpload.add(entry);
			}
		}

		Set<String> deletedPaths = new HashSet<>(previousManifest.entries().keySet());
		deletedPaths.removeAll(localFingerprints.keySet());

		long totalUploadBytes = 0L;
		for (Map.Entry<String, FileFingerprint> entry : toUpload) {
			totalUploadBytes += entry.getValue().sizeBytes();
		}
		final long totalBytes = totalUploadBytes;
		int totalFiles = toUpload.size() + deletedPaths.size();
		int unchangedCount = updatedEntries.size();
		long diffDoneAt = System.nanoTime();

		if (cancelled.get()) {
			return unchangedResult(previousManifest, totalLocalBytes, millis(walkHashStartedAt, walkHashDoneAt), millis(walkHashDoneAt, diffDoneAt), 0);
		}

		List<String> pathsToUpload = new ArrayList<>();
		for (Map.Entry<String, FileFingerprint> entry : toUpload) {
			pathsToUpload.add(entry.getKey());
		}
		preResolveFolders(drive, pathsToUpload, folderIdCache, cancelled);
		long folderPrePassDoneAt = System.nanoTime();

		if (cancelled.get()) {
			return unchangedResult(
				previousManifest, totalLocalBytes, millis(walkHashStartedAt, walkHashDoneAt), millis(walkHashDoneAt, diffDoneAt), millis(diffDoneAt, folderPrePassDoneAt)
			);
		}

		AtomicLong bytesTransferred = new AtomicLong();
		AtomicInteger filesTransferred = new AtomicInteger();
		AtomicInteger skippedCount = new AtomicInteger();
		listener.onProgress(0, totalBytes, 0, totalFiles, "");

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
						drive, folderId, relative.getFileName().toString(), new FileContent("application/octet-stream", localRoot.resolve(relativePath).toFile()),
						uploadMode == UploadMode.UPDATE_IN_PLACE ? local.driveFileId() : null
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
				listener.onProgress(newBytesTransferred, totalBytes, newFilesTransferred, totalFiles, relativePath);
			}, pool));
		}
		CompletableFuture.allOf(uploadFutures.toArray(CompletableFuture[]::new)).join();
		long uploadDoneAt = System.nanoTime();

		Set<String> unattemptedDeletes = new HashSet<>();
		if (uploadMode == UploadMode.UPDATE_IN_PLACE) {
			unattemptedDeletes.addAll(deletedPaths);
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
		if (uploadMode == UploadMode.UPDATE_IN_PLACE) {
			for (String relativePath : unattemptedDeletes) {
				updatedEntries.put(relativePath, previousManifest.entries().get(relativePath));
			}
		}

		return new SyncResult(
			updatedEntries,
			folderIdCache,
			toUpload.size(),
			deletedPaths.size(),
			unchangedCount,
			skippedCount.get(),
			filesTransferred.get(),
			totalLocalBytes,
			millis(walkHashStartedAt, walkHashDoneAt),
			millis(walkHashDoneAt, diffDoneAt),
			millis(diffDoneAt, folderPrePassDoneAt),
			millis(folderPrePassDoneAt, uploadDoneAt),
			millis(uploadDoneAt, deleteDoneAt)
		);
	}

	private static long millis(long fromNanos, long toNanos) {
		return (toNanos - fromNanos) / 1_000_000L;
	}

	private static SyncResult unchangedResult(DriveManifest previousManifest, long totalLocalBytes, long walkHashMillis, long diffMillis, long folderPrePassMillis) {
		return new SyncResult(
			previousManifest.entries(), previousManifest.folderIds(), 0, 0, previousManifest.entries().size(), 0, 0, totalLocalBytes, walkHashMillis, diffMillis, folderPrePassMillis, 0L, 0L
		);
	}

	public static boolean download(
		Drive drive, String folderId, Path targetDir, Set<String> rootSkipNames, long totalBytes, int totalFiles, TransferProgressListener listener, AtomicBoolean cancelled
	) throws IOException {
		return downloadDirectory(drive, folderId, targetDir, true, rootSkipNames, totalBytes, totalFiles, new AtomicLong(), new AtomicInteger(), listener, cancelled);
	}

	private static boolean downloadDirectory(
		Drive drive,
		String folderId,
		Path targetDir,
		boolean isRoot,
		Set<String> rootSkipNames,
		long totalBytes,
		int totalFiles,
		AtomicLong bytesTransferred,
		AtomicInteger filesTransferred,
		TransferProgressListener listener,
		AtomicBoolean cancelled
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
			if (isRoot && rootSkipNames.contains(name)) {
				continue;
			}

			if (GoogleDriveFolders.DRIVE_FOLDER_MIME_TYPE.equals(child.getMimeType())) {
				if (!downloadDirectory(drive, child.getId(), targetDir.resolve(name), false, rootSkipNames, totalBytes, totalFiles, bytesTransferred, filesTransferred, listener, cancelled)) {
					return false;
				}
			} else {
				try (InputStream in = drive.files().get(child.getId()).executeMediaAsInputStream()) {
					Files.copy(in, targetDir.resolve(name));
				}
				long newBytesTransferred = bytesTransferred.addAndGet(child.getSize() != null ? child.getSize() : 0L);
				int newFilesTransferred = filesTransferred.incrementAndGet();
				listener.onProgress(newBytesTransferred, totalBytes, newFilesTransferred, totalFiles, name);
			}
		}
		return true;
	}

	private static void preResolveFolders(Drive drive, List<String> relativeFilePaths, Map<String, String> folderIdCache, AtomicBoolean cancelled) throws IOException {
		Set<String> ancestorDirs = new TreeSet<>();
		for (String relativePath : relativeFilePaths) {
			Path parent = Path.of(relativePath).getParent();
			while (parent != null) {
				ancestorDirs.add(toKey(parent));
				parent = parent.getParent();
			}
		}

		for (String dirPath : ancestorDirs) {
			if (cancelled.get()) {
				return;
			}
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

	private static Map<String, FileFingerprint> computeLocalFingerprints(Path localRoot, DriveManifest previousManifest, BiPredicate<Path, Path> exclusionFilter) throws IOException {
		Map<String, FileFingerprint> result = new LinkedHashMap<>();
		Path normalizedRoot = localRoot.toAbsolutePath().normalize();

		Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(normalizedRoot) && exclusionFilter.test(normalizedRoot, dir)) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (exclusionFilter.test(normalizedRoot, file)) {
					return FileVisitResult.CONTINUE;
				}

				String relativePath = normalizedRoot.relativize(file).toString().replace(java.io.File.separatorChar, '/');
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

	public static GenericJson toJson(DriveManifest manifest) {
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

		return json;
	}

	public static byte[] serializeManifest(DriveManifest manifest) throws IOException {
		return GoogleDriveAuth.JSON_FACTORY.toByteArray(toJson(manifest));
	}

	public static DriveManifest fromJson(Map<?, ?> json) {
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

		return new DriveManifest(entries, folderIds);
	}

	public static DriveManifest deserializeManifest(InputStream in) throws IOException {
		GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);
		return fromJson(json);
	}
}
