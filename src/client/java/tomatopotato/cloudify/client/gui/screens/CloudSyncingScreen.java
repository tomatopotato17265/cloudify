package tomatopotato.cloudify.client.gui.screens;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tomatopotato.cloudify.client.drive.GoogleDriveLogin;
import tomatopotato.cloudify.client.drive.GoogleDriveLoopbackServer;

public class CloudSyncingScreen extends Screen {
	private static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
	private static final Identifier INWORLD_MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);
	private final Screen lastScreen;
	private GoogleDriveLoopbackServer loginServer;

	public CloudSyncingScreen(Screen lastScreen) {
		super(Component.translatable("options.cloud_syncing.title"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);

		LinearLayout content = this.layout.addToContents(LinearLayout.vertical()).spacing(8);
		content.defaultCellSetting().alignHorizontallyCenter();

		try {
			this.loginServer = GoogleDriveLoopbackServer.start();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		URI authorizationUrl = GoogleDriveLogin.buildAuthorizationUrl(this.loginServer);

		Component googleDriveLoginLabel = Component.translatable("options.cloud_syncing.google_drive_login")
			.withStyle(style -> style.withUnderlined(true).withColor(ChatFormatting.BLUE).withClickEvent(new ClickEvent.OpenUrl(authorizationUrl)));
		FocusableTextWidget googleDriveLoginWidget = FocusableTextWidget.builder(googleDriveLoginLabel, this.font)
			.alwaysShowBorder(false)
			.backgroundFill(FocusableTextWidget.BackgroundFill.NEVER)
			.build();
		googleDriveLoginWidget.setComponentClickHandler(style -> {
			if (style.getClickEvent() instanceof ClickEvent.OpenUrl(URI uri)) {
				ConfirmLinkScreen.confirmLinkNow(this, uri);
			}
		});
		googleDriveLoginWidget.setNarrateMessage(false);
		content.addChild(googleDriveLoginWidget);

		Component autoSyncLabel = Component.translatable("options.cloud_syncing.auto_sync");
		LinearLayout autoSyncRow = content.addChild(LinearLayout.horizontal()).spacing(8);
		autoSyncRow.defaultCellSetting().alignVerticallyMiddle();
		autoSyncRow.addChild(new StringWidget(autoSyncLabel, this.font));
		autoSyncRow.addChild(
			CycleButton.booleanBuilder(Component.translatable("options.cloud_syncing.after_launch"), Component.translatable("options.cloud_syncing.before_shutdown"), true)
				.displayOnlyValue()
				.create(autoSyncLabel, (button, value) -> {})
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
		if (this.loginServer != null) {
			this.loginServer.close();
		}
		this.minecraft.gui.setScreen(this.lastScreen);
	}
}
