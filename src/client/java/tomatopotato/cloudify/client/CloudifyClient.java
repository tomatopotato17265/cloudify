package tomatopotato.cloudify.client;

import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync.WorldMetadata;

public class CloudifyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Path worldFolder = server.getWorldPath(LevelResource.ROOT);
			String worldName = worldFolder.normalize().getFileName().toString();
			WorldMetadata metadata = gatherMetadata(server);
			Path iconFile = server.getWorldPath(LevelResource.ICON_FILE);
			new Thread(() -> GoogleDriveWorldSync.uploadWorld(worldFolder, worldName, metadata, iconFile), "cloudify-world-upload").start();
		});
	}

	private static WorldMetadata gatherMetadata(MinecraftServer server) {
		try {
			return new WorldMetadata(
				server.getWorldData().getLevelName(),
				server.getDefaultGameType().getName(),
				server.getWorldData().isHardcore(),
				SharedConstants.getCurrentVersion().name(),
				System.currentTimeMillis()
			);
		} catch (Exception e) {
			Cloudify.LOGGER.warn("Failed to gather world metadata for Google Drive upload", e);
			return new WorldMetadata("", "", false, "", System.currentTimeMillis());
		}
	}
}
