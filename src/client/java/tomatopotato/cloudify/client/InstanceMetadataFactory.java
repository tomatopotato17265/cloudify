package tomatopotato.cloudify.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync.InstanceMetadata;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync.ModEntry;

public class InstanceMetadataFactory {
	public static InstanceMetadata gather(String displayName) {
		try {
			List<ModEntry> mods = new ArrayList<>();
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				mods.add(new ModEntry(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
			}

			String fabricLoaderVersion = FabricLoader.getInstance()
				.getModContainer("fabricloader")
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("");

			return new InstanceMetadata(displayName, SharedConstants.getCurrentVersion().name(), fabricLoaderVersion, mods, 0L, 0L, 0);
		} catch (Exception e) {
			Cloudify.LOGGER.warn("Failed to gather instance metadata for Google Drive sync", e);
			return new InstanceMetadata(displayName, "", "", List.of(), 0L, 0L, 0);
		}
	}
}
