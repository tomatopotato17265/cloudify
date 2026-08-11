package tomatopotato.cloudify.client.gui.screens;

import java.io.IOException;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveLogin;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync.DriveWorldEntry;

public class ImportWorldScreen extends Screen {
	private enum State {
		NOT_LOGGED_IN,
		LOADING,
		LOADED,
		ERROR
	}

	private final Screen lastScreen;
	private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);
	private State state = State.LOADING;
	private List<DriveWorldEntry> entries = List.of();

	public ImportWorldScreen(Screen lastScreen) {
		super(Component.translatable("options.select_world.import_world.title"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		boolean loggedIn;
		try {
			loggedIn = GoogleDriveLogin.isLoggedIn();
		} catch (IOException e) {
			Cloudify.LOGGER.error("Failed to check Google Drive login state", e);
			this.state = State.ERROR;
			this.rebuild();
			return;
		}

		if (!loggedIn) {
			this.state = State.NOT_LOGGED_IN;
			this.rebuild();
			return;
		}

		this.state = State.LOADING;
		this.rebuild();
		this.loadWorlds();
	}

	private void loadWorlds() {
		GoogleDriveWorldSync.listWorldsAsync().handleAsync((result, error) -> {
			if (this.minecraft.gui.screen() != this) {
				return null;
			}

			if (error != null) {
				Cloudify.LOGGER.error("Failed to list Google Drive worlds", error);
				this.state = State.ERROR;
			} else {
				this.entries = result;
				this.state = State.LOADED;
			}

			this.rebuild();
			return null;
		}, this.minecraft);
	}

	private void rebuild() {
		this.clearWidgets();
		this.layout = new HeaderAndFooterLayout(this, 33);
		this.layout.addTitleHeader(this.title, this.font);

		LinearLayout content = this.layout.addToContents(LinearLayout.vertical()).spacing(8);
		content.defaultCellSetting().alignHorizontallyCenter();
		content.addChild(new StringWidget(this.createStatusMessage(), this.font));

		LinearLayout buttonRow = LinearLayout.horizontal().spacing(4);
		buttonRow.addChild(Button.builder(Component.translatable("options.select_world.import_world.import"), button -> {}).width(98).build());
		buttonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(98).build());
		this.layout.addToFooter(buttonRow);

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private Component createStatusMessage() {
		return switch (this.state) {
			case NOT_LOGGED_IN -> Component.translatable("options.select_world.import_world.not_logged_in");
			case LOADING -> Component.translatable("options.select_world.import_world.loading");
			case ERROR -> Component.translatable("options.select_world.import_world.error");
			case LOADED -> Component.translatable("options.select_world.import_world.loaded", this.entries.size());
		};
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
