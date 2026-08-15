package tomatopotato.cloudify.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync.InstanceMetadata;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync.WorldMetadata;

public class CloudifyClient implements ClientModInitializer {
	private static final String AUTO_SYNC_INSTANCE_TARGET_NAME = "Auto Backup";

	private static final ExecutorService UPLOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "cloudify-world-upload");
		thread.setDaemon(true);
		return thread;
	});

	private static final ExecutorService INSTANCE_UPLOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "cloudify-instance-upload");
		thread.setDaemon(true);
		return thread;
	});

	@Override
	public void onInitializeClient() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Path worldFolder = server.getWorldPath(LevelResource.ROOT);
			String worldName = worldFolder.normalize().getFileName().toString();
			WorldMetadata metadata = gatherMetadata(server);
			Path iconFile = server.getWorldPath(LevelResource.ICON_FILE);
			UPLOAD_EXECUTOR.execute(() -> GoogleDriveWorldSync.uploadWorld(worldFolder, worldName, metadata, iconFile));

			if (CloudifySettings.load().autoSyncInstance()) {
				Path gameDir = FabricLoader.getInstance().getGameDir();
				InstanceMetadata instanceMetadata = InstanceMetadataFactory.gather(AUTO_SYNC_INSTANCE_TARGET_NAME);
				INSTANCE_UPLOAD_EXECUTOR.execute(() -> {
					try {
						GoogleDriveInstanceSync.syncInstance(gameDir, AUTO_SYNC_INSTANCE_TARGET_NAME, instanceMetadata);
					} catch (IOException e) {
						Cloudify.LOGGER.error("Failed to auto-sync instance to Google Drive", e);
					}
				});
			}
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
