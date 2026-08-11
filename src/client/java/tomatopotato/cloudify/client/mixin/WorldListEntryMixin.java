package tomatopotato.cloudify.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tomatopotato.cloudify.client.gui.screens.DeleteWorldScreen;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {
	@Shadow
	private Minecraft minecraft;

	@Shadow
	private WorldSelectionList list;

	@Shadow
	public abstract void doDeleteWorld();

	@Inject(method = "deleteWorld", at = @At("HEAD"), cancellable = true)
	private void cloudify$deleteWorld(CallbackInfo ci) {
		this.minecraft.gui.setScreen(
			new DeleteWorldScreen(
				result -> {
					if (result) {
						this.minecraft.gui.setScreen(new ProgressScreen(true));
						this.doDeleteWorld();
					}

					this.list.returnToScreen();
				},
				Component.translatable("options.select_world.delete_what")
			)
		);
		ci.cancel();
	}
}
