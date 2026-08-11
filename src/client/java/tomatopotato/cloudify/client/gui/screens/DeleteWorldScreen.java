package tomatopotato.cloudify.client.gui.screens;

import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class DeleteWorldScreen extends Screen {
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);
	private final Screen lastScreen;

	public DeleteWorldScreen(Screen lastScreen) {
		super(Component.translatable("options.select_world.delete_world.title"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);
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
