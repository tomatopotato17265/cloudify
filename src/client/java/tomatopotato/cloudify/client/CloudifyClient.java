package tomatopotato.cloudify.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.CloudifySettings.AutoSyncMode;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync.InstanceMetadata;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync.WorldMetadata;
import tomatopotato.cloudify.drive.GoogleDriveAuth;

public class CloudifyClient implements ClientModInitializer {
	public static final String AUTO_SYNC_INSTANCE_TARGET_NAME = "Auto Backup";
	public static final AtomicBoolean SHUTDOWN_SYNC_HANDLED = new AtomicBoolean(false);

	private static final ExecutorService UPLOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "cloudify-world-upload");
		thread.setDaemon(true);
		return thread;
	});

	@Override
	public void onInitializeClient() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Path worldFolder = server.getWorldPath(LevelResource.ROOT);
			String worldName = worldFolder.normalize().getFileName().toString();
			WorldMetadata metadata = gatherMetadata(server);
			UPLOAD_EXECUTOR.execute(() -> {
				try {
					GoogleDriveWorldSync.uploadWorld(worldFolder, worldName, metadata);
				} catch (IOException e) {
					Cloudify.LOGGER.error("Failed to upload world '{}' to Google Drive", worldName, e);
				}
			});
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			try {
				if (SHUTDOWN_SYNC_HANDLED.get() || CloudifySettings.load().autoSyncMode() != AutoSyncMode.BEFORE_SHUTDOWN) {
					return;
				}

				Path gameDir = FabricLoader.getInstance().getGameDir();
				InstanceMetadata instanceMetadata = InstanceMetadataFactory.gather(AUTO_SYNC_INSTANCE_TARGET_NAME);
				CompletableFuture<Void> future = GoogleDriveInstanceSync.syncInstanceAsync(gameDir, AUTO_SYNC_INSTANCE_TARGET_NAME, instanceMetadata);
				try {
					future.get(30, TimeUnit.SECONDS);
				} catch (TimeoutException e) {
					Cloudify.LOGGER.warn("Instance auto-sync before shutdown did not finish within 30 seconds, proceeding with shutdown anyway", e);
				} catch (ExecutionException e) {
					Cloudify.LOGGER.error("Failed to auto-sync instance to Google Drive before shutdown", e.getCause());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			} finally {
				GoogleDriveAuth.shutdownTransport();
			}
		});

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			if (CloudifySettings.load().autoSyncMode() != AutoSyncMode.AFTER_LAUNCH) {
				return;
			}

			Path gameDir = FabricLoader.getInstance().getGameDir();
			InstanceMetadata instanceMetadata = InstanceMetadataFactory.gather(AUTO_SYNC_INSTANCE_TARGET_NAME);
			GoogleDriveInstanceSync.syncInstanceAsync(gameDir, AUTO_SYNC_INSTANCE_TARGET_NAME, instanceMetadata).exceptionally(e -> {
				Cloudify.LOGGER.error("Failed to auto-sync instance to Google Drive after launch", e);
				return null;
			});
		});
	}

	private static WorldMetadata gatherMetadata(MinecraftServer server) {
		try {
			return new WorldMetadata(
				server.getWorldData().getLevelName(),
				server.getDefaultGameType().getName(),
				server.getWorldData().isHardcore(),
				SharedConstants.getCurrentVersion().name(),
				readLastPlayed(server)
			);
		} catch (Exception e) {
			Cloudify.LOGGER.warn("Failed to gather world metadata for Google Drive upload", e);
			return new WorldMetadata("", "", false, "", System.currentTimeMillis());
		}
	}

	private static long readLastPlayed(MinecraftServer server) {
		try {
			return Files.getLastModifiedTime(server.getWorldPath(LevelResource.LEVEL_DATA_FILE)).toMillis();
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to read level.dat modification time, falling back to current time", e);
			return System.currentTimeMillis();
		}
	}
}
