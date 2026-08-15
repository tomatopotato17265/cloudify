package tomatopotato.cloudify.client.gui.screens;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.InstanceMetadataFactory;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync.InstanceMetadata;

public class BackupInstanceScreen extends Screen {
	private final Screen lastScreen;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);
	private EditBox nameBox;

	public BackupInstanceScreen(Screen lastScreen) {
		super(Component.translatable("options.backup_instance.title"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);

		LinearLayout content = this.layout.addToContents(LinearLayout.vertical()).spacing(8);
		content.defaultCellSetting().alignHorizontallyCenter();
		content.addChild(new StringWidget(Component.translatable("options.backup_instance.name_prompt"), this.font));

		this.nameBox = new EditBox(this.font, 200, 20, Component.translatable("options.backup_instance.name_prompt"));
		this.nameBox.setValue(defaultBackupName());
		this.nameBox.setMaxLength(64);
		content.addChild(this.nameBox);

		LinearLayout buttonRow = LinearLayout.horizontal().spacing(4);
		buttonRow.addChild(Button.builder(Component.translatable("options.backup_instance.backup"), button -> this.confirmBackup()).width(98).build());
		buttonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(98).build());
		this.layout.addToFooter(buttonRow);

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private static String defaultBackupName() {
		return "Backup " + DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDate.now());
	}

	private void confirmBackup() {
		String name = this.nameBox.getValue().trim();
		if (name.isEmpty()) {
			return;
		}

		this.minecraft.gui.setScreen(
			new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						this.beginBackup(name);
					} else {
						this.minecraft.gui.setScreen(this);
					}
				},
				Component.translatable("options.backup_instance.confirm.title"),
				Component.translatable("options.backup_instance.confirm.message", name)
			)
		);
	}

	private void beginBackup(String name) {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		InstanceMetadata metadata = InstanceMetadataFactory.gather(name);

		this.minecraft.gui.setScreen(
			new InstanceTransferProgressScreen(
				Component.translatable("options.backup_instance.syncing"),
				this.lastScreen,
				(listener, cancelled) -> GoogleDriveInstanceSync.syncInstanceAsync(gameDir, name, metadata, listener, cancelled),
				() -> this.minecraft.gui.setScreen(this.lastScreen),
				error -> {
					Cloudify.LOGGER.error("Failed to back up instance '{}' to Google Drive", name, error);
					this.minecraft.gui.setScreen(
						new AlertScreen(
							() -> this.minecraft.gui.setScreen(this.lastScreen),
							Component.translatable("options.backup_instance.failed.title"),
							Component.translatable("options.backup_instance.failed.message")
						)
					);
				}
			)
		);
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
