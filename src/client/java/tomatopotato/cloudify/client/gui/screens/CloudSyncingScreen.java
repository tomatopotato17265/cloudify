package tomatopotato.cloudify.client.gui.screens;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class CloudSyncingScreen extends Screen {
	private static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
	private static final Identifier INWORLD_MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
	private static final Component TITLE = Component.literal("Cloud Syncing Options");
	private static final Component AUTO_SYNC_LABEL = Component.literal("Automatically sync instance");
	private static final Component AFTER_LAUNCH = Component.literal("after launch");
	private static final Component BEFORE_SHUTDOWN = Component.literal("before shutdown");
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);
	private final Screen lastScreen;

	public CloudSyncingScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(TITLE, this.font);

		LinearLayout autoSyncRow = this.layout.addToContents(LinearLayout.horizontal()).spacing(8);
		autoSyncRow.defaultCellSetting().alignVerticallyMiddle();
		autoSyncRow.addChild(new StringWidget(AUTO_SYNC_LABEL, this.font));
		autoSyncRow.addChild(
			CycleButton.booleanBuilder(AFTER_LAUNCH, BEFORE_SHUTDOWN, true).displayOnlyValue().create(AUTO_SYNC_LABEL, (button, value) -> {})
		);

		this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int contentTop = this.layout.getHeaderHeight();
		int contentBottom = this.height - this.layout.getFooterHeight();
		Identifier menuListBackground = this.minecraft.level == null ? MENU_LIST_BACKGROUND : INWORLD_MENU_LIST_BACKGROUND;
		graphics.blit(RenderPipelines.GUI_TEXTURED, menuListBackground, 0, contentTop, 0.0F, 0.0F, this.width, contentBottom - contentTop, 32, 32);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		Identifier headerSeparator = this.minecraft.level == null ? Screen.HEADER_SEPARATOR : Screen.INWORLD_HEADER_SEPARATOR;
		graphics.blit(RenderPipelines.GUI_TEXTURED, headerSeparator, 0, this.layout.getHeaderHeight() - 2, 0.0F, 0.0F, this.width, 2, 32, 2);
		Identifier footerSeparator = this.minecraft.level == null ? Screen.FOOTER_SEPARATOR : Screen.INWORLD_FOOTER_SEPARATOR;
		graphics.blit(RenderPipelines.GUI_TEXTURED, footerSeparator, 0, this.height - this.layout.getFooterHeight(), 0.0F, 0.0F, this.width, 2, 32, 2);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}
}
