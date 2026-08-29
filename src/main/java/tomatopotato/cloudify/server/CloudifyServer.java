package tomatopotato.cloudify.server;

import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.server.CloudifyServerSettings.CloudifyServerSettingsData;
import tomatopotato.cloudify.server.drive.GoogleDriveServerSync;

public class CloudifyServer implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		Cloudify.LOGGER.info("Cloudify server-side backups are initializing");
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CloudifyServerCommands.register(dispatcher));
		CloudifyServerScheduler.register();

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			CloudifyServerSettingsData settings = CloudifyServerSettings.load();
			if (settings.backupIntervalMinutes() <= 0) {
				return;
			}

			Path serverRoot = FabricLoader.getInstance().getGameDir();
			try {
				boolean started = GoogleDriveServerSync.triggerBackupBlocking(server, serverRoot, settings.serverDisplayName());
				if (!started) {
					Cloudify.LOGGER.info("Skipping shutdown backup: a backup is already in progress");
				}
			} catch (IOException e) {
				Cloudify.LOGGER.error("Shutdown backup failed", e);
			}
		});
	}
}
