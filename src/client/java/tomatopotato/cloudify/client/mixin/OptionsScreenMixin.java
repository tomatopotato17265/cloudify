package tomatopotato.cloudify.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import tomatopotato.cloudify.client.gui.screens.CloudSyncingScreen;

@Mixin(OptionsScreen.class)
public class OptionsScreenMixin {
	@ModifyArg(
		method = "init",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"
		)
	)
	private LayoutElement cloudify$addBackupButton(LayoutElement layoutElement) {
		if (layoutElement instanceof GridLayout gridLayout) {
			gridLayout.addChild(
				Button.builder(Component.translatable("options.backup"), button -> {
					OptionsScreen self = (OptionsScreen) (Object) this;
					Minecraft.getInstance().gui.setScreen(new CloudSyncingScreen(self));
				}).build(),
				5,
				0
			);
		}
		return layoutElement;
	}
}
