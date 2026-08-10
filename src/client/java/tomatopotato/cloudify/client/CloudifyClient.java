package tomatopotato.cloudify.client;

import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync;

public class CloudifyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Path worldFolder = server.getWorldPath(LevelResource.ROOT);
			String worldName = worldFolder.normalize().getFileName().toString();
			new Thread(() -> GoogleDriveWorldSync.uploadWorld(worldFolder, worldName), "cloudify-world-upload").start();
		});
	}
}
