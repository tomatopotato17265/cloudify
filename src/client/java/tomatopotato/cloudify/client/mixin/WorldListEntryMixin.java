package tomatopotato.cloudify.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync;
import tomatopotato.cloudify.client.gui.screens.DeleteWorldScreen;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {
	@Shadow
	private Minecraft minecraft;

	@Shadow
	private WorldSelectionList list;

	@Shadow
	private LevelSummary summary;

	@Shadow
	public abstract void doDeleteWorld();

	@Inject(method = "deleteWorld", at = @At("HEAD"), cancellable = true)
	private void cloudify$deleteWorld(CallbackInfo ci) {
		this.minecraft.gui.setScreen(
			new DeleteWorldScreen(
				scope -> {
					if (scope == DeleteWorldScreen.DeleteScope.LOCAL || scope == DeleteWorldScreen.DeleteScope.BOTH) {
						this.minecraft.gui.setScreen(new ProgressScreen(true));
						this.doDeleteWorld();
					}

					if (scope == DeleteWorldScreen.DeleteScope.CLOUD || scope == DeleteWorldScreen.DeleteScope.BOTH) {
						String worldName = this.summary.getLevelId();
						GoogleDriveWorldSync.deleteWorldAsync(worldName).exceptionally(error -> {
							Cloudify.LOGGER.error("Failed to delete world '{}' from Google Drive", worldName, error);
							return null;
						});
					}

					this.list.returnToScreen();
				},
				this.list::returnToScreen,
				Component.translatable("options.select_world.delete_what"),
				this.summary.getLevelName()
			)
		);
		ci.cancel();
	}
}
