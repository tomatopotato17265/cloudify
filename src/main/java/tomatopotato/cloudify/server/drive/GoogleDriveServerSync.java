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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

	public static void backupServer(Path serverRoot, String serverName) throws IOException {
		backupServer(serverRoot, serverName, TransferProgressListener.NO_OP, new AtomicBoolean(false));
	}

	public static void backupServer(Path serverRoot, String serverName, TransferProgressListener listener, AtomicBoolean cancelled) throws IOException {
		if (!GoogleDriveDeviceAuth.isLoggedIn()) {
			Cloudify.LOGGER.info("Server backup for '{}' skipped: not logged in to Google Drive", serverName);
			return;
		}

		long startedAt = System.nanoTime();
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

		Cloudify.LOGGER.info(
			"Backed up server '{}' to Google Drive as '{}' ({} files changed/added, {} gone locally, {} unchanged, {} skipped, {} bytes, {} requests) in {} ms"
				+ " [bootstrap {} / sync {} / commit {}]",
			serverName,
			backupFolderName,
			result.uploadedCount(),
			result.deletedCount(),
			result.unchangedCount(),
			result.skippedCount(),
			result.totalLocalBytes(),
			GoogleDriveFolders.getRequestCount(),
			millis(startedAt, commitDoneAt),
			millis(startedAt, bootstrapDoneAt),
			millis(bootstrapDoneAt, syncDoneAt),
			millis(syncDoneAt, commitDoneAt)
		);
	}

	private static long millis(long fromNanos, long toNanos) {
		return (toNanos - fromNanos) / 1_000_000L;
	}
}
