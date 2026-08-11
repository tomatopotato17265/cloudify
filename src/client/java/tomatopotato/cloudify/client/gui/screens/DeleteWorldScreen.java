package tomatopotato.cloudify.client.gui.screens;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class DeleteWorldScreen extends Screen {
	private enum DeleteScope {
		LOCAL,
		CLOUD,
		BOTH
	}

	private final BooleanConsumer callback;
	private final LinearLayout layout = LinearLayout.vertical().spacing(8);

	public DeleteWorldScreen(BooleanConsumer callback, Component title) {
		super(title);
		this.callback = callback;
	}

	@Override
	protected void init() {
		this.layout.defaultCellSetting().alignHorizontallyCenter();
		this.layout.addChild(new StringWidget(this.title, this.font));
		this.layout.addChild(
			CycleButton.builder(DeleteWorldScreen::deleteScopeLabel, DeleteScope.LOCAL)
				.withValues(DeleteScope.values())
				.displayOnlyValue()
				.create(Component.empty(), (button, value) -> {})
		);

		LinearLayout buttonRow = this.layout.addChild(LinearLayout.horizontal().spacing(4));
		buttonRow.defaultCellSetting().paddingTop(16);
		buttonRow.addChild(Button.builder(Component.translatable("selectWorld.deleteButton"), button -> this.callback.accept(true)).build());
		buttonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.callback.accept(false)).build());

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private static Component deleteScopeLabel(DeleteScope scope) {
		return switch (scope) {
			case LOCAL -> Component.translatable("options.select_world.delete_scope.local");
			case CLOUD -> Component.translatable("options.select_world.delete_scope.cloud");
			case BOTH -> Component.translatable("options.select_world.delete_scope.both");
		};
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		FrameLayout.centerInRectangle(this.layout, this.getRectangle());
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
