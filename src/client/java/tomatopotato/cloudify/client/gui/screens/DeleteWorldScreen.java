package tomatopotato.cloudify.client.gui.screens;

import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class DeleteWorldScreen extends Screen {
	public enum DeleteScope {
		LOCAL,
		CLOUD,
		BOTH
	}

	private final Consumer<DeleteScope> onDelete;
	private final Runnable onCancel;
	private final String levelName;
	private final LinearLayout layout = LinearLayout.vertical().spacing(8);
	private DeleteScope selectedScope = DeleteScope.LOCAL;

	public DeleteWorldScreen(Consumer<DeleteScope> onDelete, Runnable onCancel, Component title, String levelName) {
		super(title);
		this.onDelete = onDelete;
		this.onCancel = onCancel;
		this.levelName = levelName;
	}

	@Override
	protected void init() {
		this.layout.defaultCellSetting().alignHorizontallyCenter();
		this.layout.addChild(new StringWidget(this.title, this.font));
		this.layout.addChild(
			CycleButton.builder(DeleteWorldScreen::deleteScopeLabel, DeleteScope.LOCAL)
				.withValues(DeleteScope.values())
				.displayOnlyValue()
				.create(Component.empty(), (button, value) -> this.selectedScope = value)
		);

		LinearLayout buttonRow = this.layout.addChild(LinearLayout.horizontal().spacing(4));
		buttonRow.defaultCellSetting().paddingTop(16);
		buttonRow.addChild(Button.builder(Component.translatable("selectWorld.deleteButton"), button -> this.confirmDelete()).build());
		buttonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onCancel.run()).build());

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private void confirmDelete() {
		this.minecraft.gui.setScreen(
			new ConfirmScreen(
				result -> {
					if (result) {
						this.onDelete.accept(this.selectedScope);
					} else {
						this.onCancel.run();
					}
				},
				Component.translatable("selectWorld.deleteQuestion"),
				Component.translatable("selectWorld.deleteWarning", this.levelName),
				Component.translatable("options.select_world.delete_confirm"),
				CommonComponents.GUI_CANCEL
			)
		);
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
