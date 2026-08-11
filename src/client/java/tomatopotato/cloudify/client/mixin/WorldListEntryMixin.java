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
import tomatopotato.cloudify.client.gui.screens.DeleteWorldConfirmScreen;

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
			new DeleteWorldConfirmScreen(
				result -> {
					if (result) {
						this.minecraft.gui.setScreen(new ProgressScreen(true));
						this.doDeleteWorld();
					}

					this.list.returnToScreen();
				},
				Component.translatable("selectWorld.deleteQuestion"),
				Component.translatable("selectWorld.deleteWarning", this.summary.getLevelName())
			)
		);
		ci.cancel();
	}
}
