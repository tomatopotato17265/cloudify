package tomatopotato.cloudify.server;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import tomatopotato.cloudify.Cloudify;

public class CloudifyServer implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		Cloudify.LOGGER.info("Cloudify server-side backups are initializing");
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CloudifyServerCommands.register(dispatcher));
	}
}
