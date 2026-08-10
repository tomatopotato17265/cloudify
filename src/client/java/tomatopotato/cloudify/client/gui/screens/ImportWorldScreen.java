package tomatopotato.cloudify.client.gui.screens;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class ImportWorldScreen extends Screen {
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);
	private final Screen lastScreen;

	public ImportWorldScreen(Screen lastScreen) {
		super(Component.translatable("options.select_world.import_world.title"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);

		LinearLayout buttonRow = LinearLayout.horizontal().spacing(4);
		buttonRow.addChild(Button.builder(Component.translatable("options.select_world.import_world.import"), button -> {}).width(98).build());
		buttonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(98).build());
		this.layout.addToFooter(buttonRow);

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}
}
