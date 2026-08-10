package tomatopotato.cloudify.client.mixin;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin {
	@Shadow
	@Final
	private HeaderAndFooterLayout layout;

	@Shadow
	@Final
	protected Screen lastScreen;

	@Shadow
	private Button playWorldButton;

	@Shadow
	private Button editButton;

	@Shadow
	private Button deleteButton;

	@Shadow
	private Button recreateButton;

	@Inject(method = "createFooterButtons", at = @At("HEAD"), cancellable = true)
	private void cloudify$createFooterButtons(Consumer<WorldSelectionList.WorldListEntry> joinWorld, WorldSelectionList list, CallbackInfo ci) {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();

		LinearLayout topRow = footer.addChild(LinearLayout.horizontal().spacing(8));
		this.playWorldButton = topRow.addChild(
			Button.builder(Component.translatable("options.select_world.play_world"), button -> list.getSelectedOpt().ifPresent(joinWorld)).width(98).build()
		);
		topRow.addChild(Button.builder(Component.translatable("options.select_world.import_world"), button -> {}).width(98).build());
		topRow.addChild(
			Button.builder(
					Component.translatable("options.select_world.create_world"),
					button -> CreateWorldScreen.openFresh(Minecraft.getInstance(), list::returnToScreen)
				)
				.width(98)
				.build()
		);

		LinearLayout bottomRow = footer.addChild(LinearLayout.horizontal().spacing(8));
		this.editButton = bottomRow.addChild(
			Button.builder(Component.translatable("selectWorld.edit"), button -> list.getSelectedOpt().ifPresent(WorldSelectionList.WorldListEntry::editWorld))
				.width(71)
				.build()
		);
		this.deleteButton = bottomRow.addChild(
			Button.builder(Component.translatable("selectWorld.delete"), button -> list.getSelectedOpt().ifPresent(WorldSelectionList.WorldListEntry::deleteWorld))
				.width(71)
				.build()
		);
		this.recreateButton = bottomRow.addChild(
			Button.builder(
					Component.translatable("selectWorld.recreate"), button -> list.getSelectedOpt().ifPresent(WorldSelectionList.WorldListEntry::recreateWorld)
				)
				.width(71)
				.build()
		);
		bottomRow.addChild(
			Button.builder(CommonComponents.GUI_BACK, button -> Minecraft.getInstance().gui.setScreen(this.lastScreen)).width(71).build()
		);

		ci.cancel();
	}

	@Inject(method = "updateButtonStatus", at = @At("TAIL"))
	private void cloudify$updateButtonStatus(LevelSummary summary, CallbackInfo ci) {
		if (this.playWorldButton != null) {
			this.playWorldButton.setMessage(Component.translatable("options.select_world.play_world"));
		}
	}
}
