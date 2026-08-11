package tomatopotato.cloudify.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
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

	@Inject(method = "deleteWorld", at = @At("HEAD"), cancellable = true)
	private void cloudify$deleteWorld(CallbackInfo ci) {
		this.minecraft.gui.setScreen(new DeleteWorldScreen(this.minecraft.gui.screen()));
		ci.cancel();
	}
}
