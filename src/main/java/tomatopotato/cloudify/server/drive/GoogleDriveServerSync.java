package tomatopotato.cloudify.server.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.drive.DriveTreeSync;
import tomatopotato.cloudify.drive.GoogleDriveFolders;
import tomatopotato.cloudify.drive.TransferProgressListener;

import static tomatopotato.cloudify.drive.GoogleDriveFolders.DRIVE_FOLDER_NAME;
import static tomatopotato.cloudify.drive.GoogleDriveFolders.SERVERS_FOLDER_NAME;

public class GoogleDriveServerSync {
	private static final String MANIFEST_FILE_NAME = "manifest.json";
	private static final int UPLOAD_CONCURRENCY = 6;
	private static final DateTimeFormatter BACKUP_FOLDER_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

	private static final AtomicBoolean BACKUP_IN_PROGRESS = new AtomicBoolean(false);
	private static final ExecutorService BACKUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "cloudify-server-backup");
		thread.setDaemon(true);
		return thread;
	});

	public record LastBackupResult(
		String serverName, String backupFolderName, int uploadedCount, int deletedCount, int unchangedCount, int skippedCount, long totalBytes, long durationMillis
	) {
	}

	private static volatile @Nullable LastBackupResult lastBackupResult;

	public static boolean isBackupInProgress() {
		return BACKUP_IN_PROGRESS.get();
	}

	public static @Nullable LastBackupResult getLastBackupResult() {
		return lastBackupResult;
	}

	public static boolean triggerBackupAsync(MinecraftServer server, Path serverRoot, String serverName) {
		if (!BACKUP_IN_PROGRESS.compareAndSet(false, true)) {
			return false;
		}

		BACKUP_EXECUTOR.execute(() -> {
			try {
				backupServer(server, serverRoot, serverName);
			} catch (IOException e) {
				Cloudify.LOGGER.error("Server backup for '{}' failed", serverName, e);
			} finally {
				BACKUP_IN_PROGRESS.set(false);
			}
		});
		return true;
	}

	public static boolean triggerBackupBlocking(MinecraftServer server, Path serverRoot, String serverName) throws IOException {
		if (!BACKUP_IN_PROGRESS.compareAndSet(false, true)) {
			return false;
		}

		try {
			backupServer(server, serverRoot, serverName);
		} finally {
			BACKUP_IN_PROGRESS.set(false);
		}
		return true;
	}

	public static void backupServer(MinecraftServer server, Path serverRoot, String serverName) throws IOException {
		backupServer(server, serverRoot, serverName, new LoggingTransferProgressListener(serverName), new AtomicBoolean(false));
	}

	public static void backupServer(MinecraftServer server, Path serverRoot, String serverName, TransferProgressListener listener, AtomicBoolean cancelled) throws IOException {
		if (!GoogleDriveDeviceAuth.isLoggedIn()) {
			Cloudify.LOGGER.info("Server backup for '{}' skipped: not logged in to Google Drive", serverName);
			return;
		}

		long startedAt = System.nanoTime();
		if (server.isSameThread()) {
			server.saveEverything(true, true, true);
		} else {
			try {
				server.submit(() -> server.saveEverything(true, true, true)).get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				Cloudify.LOGGER.warn("Interrupted while saving before server backup for '{}', proceeding with backup anyway", serverName, e);
			} catch (ExecutionException e) {
				Cloudify.LOGGER.warn("Failed to save before server backup for '{}', proceeding with backup anyway", serverName, e.getCause());
			}
		}
		long saveDoneAt = System.nanoTime();

		GoogleDriveFolders.resetRequestCount();
		Credential credential = GoogleDriveDeviceAuth.getCredential();
		Drive drive = GoogleDriveFolders.buildDriveClient(credential);

		String slug = GoogleDriveFolders.slugify(serverName, "server");
		ServerDriveIdCache.Data cache = ServerDriveIdCache.load();
		ServerDriveIdCache.ServerState previousState = cache.servers().get(slug);

		String cloudifyFolderId = cache.cloudifyFolderId();
		String serversFolderId = cache.serversFolderId();
		String serverFolderId = previousState != null ? previousState.folderId() : null;
		boolean bootstrapUsable = cloudifyFolderId != null && serversFolderId != null && serverFolderId != null && GoogleDriveFolders.isFolderUsable(drive, serverFolderId);
		if (!bootstrapUsable) {
			cloudifyFolderId = GoogleDriveFolders.findOrCreateFolder(drive, DRIVE_FOLDER_NAME, null);
			serversFolderId = GoogleDriveFolders.findOrCreateFolder(drive, SERVERS_FOLDER_NAME, cloudifyFolderId);
			serverFolderId = GoogleDriveFolders.findOrCreateFolder(drive, slug, serversFolderId);
		}
		DriveTreeSync.DriveManifest previousManifest = previousState != null ? previousState.manifest() : DriveTreeSync.DriveManifest.empty();
		long bootstrapDoneAt = System.nanoTime();

		if (cancelled.get()) {
			return;
		}

		String backupFolderName = BACKUP_FOLDER_NAME_FORMAT.format(Instant.now());
		String backupFolderId = GoogleDriveFolders.findOrCreateFolder(drive, backupFolderName, serverFolderId);

		AtomicInteger uploadThreadCounter = new AtomicInteger();
		ExecutorService uploadPool = Executors.newFixedThreadPool(UPLOAD_CONCURRENCY, r -> {
			Thread thread = new Thread(r, "cloudify-server-upload-" + uploadThreadCounter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		DriveTreeSync.SyncResult result;
		try {
			result = DriveTreeSync.sync(
				drive, backupFolderId, serverRoot, previousManifest, ServerFileFilter::isExcluded, listener, cancelled, uploadPool, DriveTreeSync.UploadMode.CREATE_ONLY
			);
		} finally {
			uploadPool.shutdown();
		}
		long syncDoneAt = System.nanoTime();

		if (cancelled.get()) {
			Cloudify.LOGGER.info("Server backup for '{}' was cancelled after {} ms, discarding this backup attempt", serverName, millis(startedAt, syncDoneAt));
			return;
		}

		DriveTreeSync.DriveManifest updatedManifest = new DriveTreeSync.DriveManifest(result.entries(), result.folderIds());
		GoogleDriveFolders.uploadFile(drive, backupFolderId, MANIFEST_FILE_NAME, new ByteArrayContent("application/json", DriveTreeSync.serializeManifest(updatedManifest)));
		long commitDoneAt = System.nanoTime();

		Map<String, ServerDriveIdCache.ServerState> updatedServers = new LinkedHashMap<>(cache.servers());
		updatedServers.put(slug, new ServerDriveIdCache.ServerState(serverFolderId, updatedManifest));
		ServerDriveIdCache.save(new ServerDriveIdCache.Data(cloudifyFolderId, serversFolderId, updatedServers));

		lastBackupResult = new LastBackupResult(
			serverName,
			backupFolderName,
			result.uploadedCount(),
			result.deletedCount(),
			result.unchangedCount(),
			result.skippedCount(),
			result.totalLocalBytes(),
			millis(startedAt, commitDoneAt)
		);

		Cloudify.LOGGER.info(
			"Backed up server '{}' to Google Drive as '{}' ({} files changed/added, {} gone locally, {} unchanged, {} skipped, {} bytes, {} requests) in {} ms"
				+ " [save {} / bootstrap {} / sync {} / commit {}]",
			serverName,
			backupFolderName,
			result.uploadedCount(),
			result.deletedCount(),
			result.unchangedCount(),
			result.skippedCount(),
			result.totalLocalBytes(),
			GoogleDriveFolders.getRequestCount(),
			millis(startedAt, commitDoneAt),
			millis(startedAt, saveDoneAt),
			millis(saveDoneAt, bootstrapDoneAt),
			millis(bootstrapDoneAt, syncDoneAt),
			millis(syncDoneAt, commitDoneAt)
		);
	}

	private static long millis(long fromNanos, long toNanos) {
		return (toNanos - fromNanos) / 1_000_000L;
	}
}
