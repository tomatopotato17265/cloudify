package tomatopotato.cloudify.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.CloudifyClient;
import tomatopotato.cloudify.client.CloudifySettings;
import tomatopotato.cloudify.client.CloudifySettings.AutoSyncMode;
import tomatopotato.cloudify.client.InstanceMetadataFactory;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync.InstanceMetadata;
import tomatopotato.cloudify.client.gui.screens.InstanceTransferProgressScreen;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
	@Redirect(method = "lambda$init$5", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;stop()V"))
	private void cloudify$interceptQuit(Minecraft minecraft) {
		CloudifyClient.SHUTDOWN_SYNC_HANDLED.set(true);

		if (CloudifySettings.load().autoSyncMode() != AutoSyncMode.BEFORE_SHUTDOWN) {
			minecraft.stop();
			return;
		}

		Path gameDir = FabricLoader.getInstance().getGameDir();
		InstanceMetadata instanceMetadata = InstanceMetadataFactory.gather(CloudifyClient.AUTO_SYNC_INSTANCE_TARGET_NAME);
		Screen self = (Screen) (Object) this;
		minecraft.gui.setScreen(
			new InstanceTransferProgressScreen(
				Component.translatable("options.backup_instance.syncing"),
				self,
				(listener, cancelled) -> GoogleDriveInstanceSync.syncInstanceAsync(
					gameDir, CloudifyClient.AUTO_SYNC_INSTANCE_TARGET_NAME, instanceMetadata, listener, cancelled
				),
				minecraft::stop,
				error -> {
					Cloudify.LOGGER.error("Failed to auto-sync instance to Google Drive before shutdown", error);
					minecraft.stop();
				}
			)
		);
	}
}
