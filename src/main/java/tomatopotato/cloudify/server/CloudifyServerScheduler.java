package tomatopotato.cloudify.server;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import tomatopotato.cloudify.server.CloudifyServerSettings.CloudifyServerSettingsData;
import tomatopotato.cloudify.server.drive.GoogleDriveServerSync;

public class CloudifyServerScheduler {
	private static final long CHECK_INTERVAL_SECONDS = 15;
	private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "cloudify-server-backup-scheduler");
		thread.setDaemon(true);
		return thread;
	});

	private static volatile long lastBackupAtMillis;

	public static void register() {
		lastBackupAtMillis = System.currentTimeMillis();
		SCHEDULER.scheduleWithFixedDelay(CloudifyServerScheduler::check, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	private static void check() {
		CloudifyServerSettingsData settings = CloudifyServerSettings.load();
		if (settings.backupIntervalMinutes() <= 0) {
			return;
		}

		long intervalMillis = settings.backupIntervalMinutes() * 60_000L;
		long now = System.currentTimeMillis();
		if (now - lastBackupAtMillis < intervalMillis) {
			return;
		}

		Path serverRoot = FabricLoader.getInstance().getGameDir();
		if (GoogleDriveServerSync.triggerBackupAsync(serverRoot, settings.serverDisplayName())) {
			lastBackupAtMillis = now;
		}
	}
}
