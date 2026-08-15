package tomatopotato.cloudify.client.gui.screens;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.InstanceRestore;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync.DriveInstanceEntry;
import tomatopotato.cloudify.client.drive.GoogleDriveLogin;

public class ImportInstanceScreen extends Screen {
	private enum State {
		NOT_LOGGED_IN,
		LOADING,
		LOADED,
		ERROR
	}

	private final Screen lastScreen;
	private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);
	private State state = State.LOADING;
	private List<DriveInstanceEntry> entries = List.of();
	private @Nullable ImportInstanceSelectionList instanceList;
	private Button restoreButton;
	private Button deleteButton;

	public ImportInstanceScreen(Screen lastScreen) {
		super(Component.translatable("options.restore_instance.title"));
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

		this.loadInstances();
	}

	private void loadInstances() {
		this.state = State.LOADING;
		this.rebuild();

		GoogleDriveInstanceSync.listInstancesAsync().handleAsync((result, error) -> {
			if (this.minecraft.gui.screen() != this) {
				return null;
			}

			if (error != null) {
				Cloudify.LOGGER.error("Failed to list Google Drive instances", error);
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
		this.instanceList = null;

		switch (this.state) {
			case NOT_LOGGED_IN -> this.buildNotLoggedIn();
			case LOADING -> this.buildLoading();
			case ERROR -> this.buildError();
			case LOADED -> this.buildLoaded();
		}

		LinearLayout buttonRow = LinearLayout.horizontal().spacing(4);
		this.restoreButton = buttonRow.addChild(
			Button.builder(Component.translatable("options.restore_instance.restore"), button -> this.confirmRestore()).width(98).build()
		);
		this.restoreButton.active = this.instanceList != null && this.instanceList.getSelectedEntry() != null;
		this.deleteButton = buttonRow.addChild(
			Button.builder(Component.translatable("options.restore_instance.delete"), button -> this.confirmDelete()).width(98).build()
		);
		this.deleteButton.active = this.instanceList != null && this.instanceList.getSelectedEntry() != null;
		buttonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(98).build());
		this.layout.addToFooter(buttonRow);

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();

		if (this.instanceList != null) {
			this.instanceList.updateSizeAndPosition(this.instanceList.getWidth(), this.instanceList.getHeight(), this.instanceList.getX(), this.instanceList.getY());
		}
	}

	private LinearLayout createMessageContent() {
		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(8));
		content.defaultCellSetting().alignHorizontallyCenter();
		return content;
	}

	private void buildNotLoggedIn() {
		LinearLayout content = this.createMessageContent();
		content.addChild(new StringWidget(Component.translatable("options.restore_instance.not_logged_in"), this.font));
		content.addChild(
			Button.builder(
					Component.translatable("options.select_world.import_world.log_in"), button -> this.minecraft.gui.setScreen(new CloudSyncingScreen(this))
				)
				.width(150)
				.build()
		);
	}

	private void buildLoading() {
		LinearLayout content = this.createMessageContent();
		content.addChild(new StringWidget(Component.translatable("options.restore_instance.loading"), this.font));
	}

	private void buildError() {
		LinearLayout content = this.createMessageContent();
		content.addChild(new StringWidget(Component.translatable("options.restore_instance.error"), this.font));
		content.addChild(Button.builder(Component.translatable("options.select_world.import_world.retry"), button -> this.loadInstances()).width(150).build());
	}

	private void buildLoaded() {
		if (this.entries.isEmpty()) {
			LinearLayout content = this.createMessageContent();
			content.addChild(new StringWidget(Component.translatable("options.restore_instance.empty"), this.font));
			return;
		}

		this.instanceList = this.layout.addToContents(
			new ImportInstanceSelectionList(this.minecraft, this.width, this.layout.getContentHeight(), this.entries, this::updateRestoreButtonState)
		);
	}

	private void updateRestoreButtonState() {
		boolean hasSelection = this.instanceList != null && this.instanceList.getSelectedEntry() != null;
		this.restoreButton.active = hasSelection;
		this.deleteButton.active = hasSelection;
	}

	private void confirmRestore() {
		if (this.instanceList == null) {
			return;
		}

		DriveInstanceEntry entry = this.instanceList.getSelectedEntry();
		if (entry == null) {
			return;
		}

		this.minecraft.gui.setScreen(
			new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						this.beginRestore(entry);
					} else {
						this.minecraft.gui.setScreen(this);
					}
				},
				Component.translatable("options.restore_instance.confirm_overwrite.title"),
				Component.translatable("options.restore_instance.confirm_overwrite.message", entry.displayName())
			)
		);
	}

	private void beginRestore(DriveInstanceEntry entry) {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path stagingDir = createStagingDir(gameDir);

		this.minecraft.gui.setScreen(
			new InstanceTransferProgressScreen(
				Component.translatable("options.restore_instance.importing"),
				this.lastScreen,
				(listener, cancelled) -> GoogleDriveInstanceSync.downloadInstanceAsync(entry, stagingDir, listener, cancelled),
				() -> this.finalizeRestore(gameDir, stagingDir),
				error -> {
					Cloudify.LOGGER.error("Failed to restore instance '{}' from Google Drive", entry.displayName(), error);
					this.minecraft.gui.setScreen(
						new AlertScreen(
							() -> this.minecraft.gui.setScreen(this.lastScreen),
							Component.translatable("options.restore_instance.failed.title"),
							Component.translatable("options.restore_instance.failed.message")
						)
					);
				}
			)
		);
	}

	private void confirmDelete() {
		if (this.instanceList == null) {
			return;
		}

		DriveInstanceEntry entry = this.instanceList.getSelectedEntry();
		if (entry == null) {
			return;
		}

		this.minecraft.gui.setScreen(
			new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						this.beginDelete(entry);
					} else {
						this.minecraft.gui.setScreen(this);
					}
				},
				Component.translatable("options.restore_instance.delete_confirm.title"),
				Component.translatable("options.restore_instance.delete_confirm.message", entry.displayName())
			)
		);
	}

	private void beginDelete(DriveInstanceEntry entry) {
		this.minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable("options.restore_instance.deleting")));

		GoogleDriveInstanceSync.deleteInstanceAsync(entry.folderId()).handleAsync((ignored, error) -> {
			if (error != null) {
				Cloudify.LOGGER.error("Failed to delete instance backup '{}' from Google Drive", entry.displayName(), error);
				this.minecraft.gui.setScreen(
					new AlertScreen(
						this::loadInstances,
						Component.translatable("options.restore_instance.delete_failed.title"),
						Component.translatable("options.restore_instance.delete_failed.message")
					)
				);
			} else {
				this.loadInstances();
			}
			return null;
		}, this.minecraft);
	}

	private void finalizeRestore(Path gameDir, Path stagingDir) {
		this.minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable("options.restore_instance.applying")));

		CompletableFuture.runAsync(() -> applyRestoreUnchecked(gameDir, stagingDir), Util.backgroundExecutor()).handleAsync((ignored, error) -> {
			if (error != null) {
				Cloudify.LOGGER.error("Failed to finalize instance restore", error);
				this.minecraft.gui.setScreen(
					new AlertScreen(
						() -> this.minecraft.gui.setScreen(this.lastScreen),
						Component.translatable("options.restore_instance.failed.title"),
						Component.translatable("options.restore_instance.failed.message")
					)
				);
			} else {
				this.minecraft.gui.setScreen(
					new AlertScreen(
						() -> this.minecraft.gui.setScreen(this.lastScreen),
						Component.translatable("options.restore_instance.complete.title"),
						Component.translatable("options.restore_instance.complete.message")
					)
				);
			}
			return null;
		}, this.minecraft);
	}

	private static void applyRestoreUnchecked(Path gameDir, Path stagingDir) {
		try {
			InstanceRestore.apply(gameDir, stagingDir);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path createStagingDir(Path gameDir) {
		try {
			return Files.createTempDirectory(gameDir.getParent(), "cloudify-instance-restore-");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
