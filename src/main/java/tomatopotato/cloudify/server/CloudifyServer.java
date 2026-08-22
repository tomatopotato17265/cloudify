package tomatopotato.cloudify.server;

import net.fabricmc.api.DedicatedServerModInitializer;
import tomatopotato.cloudify.Cloudify;

public class CloudifyServer implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		Cloudify.LOGGER.info("Cloudify server-side backups are initializing");
	}
}
