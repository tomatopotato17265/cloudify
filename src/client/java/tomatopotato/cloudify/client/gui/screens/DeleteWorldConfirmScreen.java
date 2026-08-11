package tomatopotato.cloudify.client.gui.screens;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class DeleteWorldConfirmScreen extends ConfirmScreen {
	public DeleteWorldConfirmScreen(BooleanConsumer callback, Component title, Component message) {
		super(callback, title, message, Component.translatable("selectWorld.deleteButton"), CommonComponents.GUI_CANCEL);
	}

	@Override
	protected void init() {
		super.init();

		LinearLayout duplicateSection = this.layout.addChild(LinearLayout.vertical().spacing(8), settings -> settings.paddingTop(20));
		duplicateSection.defaultCellSetting().alignHorizontallyCenter();
		duplicateSection.addChild(new StringWidget(this.title, this.font));
		duplicateSection.addChild(this.addMessage());
		LinearLayout duplicateButtonRow = duplicateSection.addChild(LinearLayout.horizontal().spacing(4));
		duplicateButtonRow.defaultCellSetting().paddingTop(16);
		this.addButtons(duplicateButtonRow);

		duplicateSection.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		int x = (this.width - this.layout.getWidth()) / 2;
		this.layout.setPosition(x, 20);
	}
}
