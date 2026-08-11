package tomatopotato.cloudify.client.gui.screens;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class DeleteWorldConfirmScreen extends ConfirmScreen {
	public DeleteWorldConfirmScreen(BooleanConsumer callback, Component title, Component message) {
		super(callback, title, message, Component.translatable("selectWorld.deleteButton"), CommonComponents.GUI_CANCEL);
	}

	@Override
	protected void addAdditionalText() {
		super.addAdditionalText();
		this.layout.addChild(
			Checkbox.builder(Component.translatable("options.select_world.delete_from_drive"), this.font)
				.selected(false)
				.onValueChange((checkbox, value) -> {})
				.build(),
			settings -> settings.paddingTop(12)
		);
	}
}
