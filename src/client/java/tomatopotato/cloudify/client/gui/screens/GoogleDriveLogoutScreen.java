package tomatopotato.cloudify.client.gui.screens;

import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveLogin;

public class GoogleDriveLogoutScreen extends ConfirmScreen {
	public GoogleDriveLogoutScreen(CloudSyncingScreen cloudSyncingScreen, String email) {
		super(
			confirmed -> onConfirm(cloudSyncingScreen, confirmed),
			Component.translatable("options.cloud_syncing.log_out"),
			Component.translatable("options.cloud_syncing.confirm_logout", email)
		);
	}

	@Override
	protected void addButtons(LinearLayout buttonLayout) {
		buttonLayout.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.callback.accept(false)).build());
		buttonLayout.addChild(Button.builder(Component.translatable("options.cloud_syncing.log_out"), button -> this.callback.accept(true)).build());
	}

	private static void onConfirm(CloudSyncingScreen cloudSyncingScreen, boolean loggedOut) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!loggedOut) {
			minecraft.gui.setScreen(cloudSyncingScreen);
			return;
		}

		try {
			GoogleDriveLogin.logout();
		} catch (IOException e) {
			Cloudify.LOGGER.error("Failed to log out of Google Drive", e);
		}
		minecraft.gui.setScreen(new CloudSyncingScreen(cloudSyncingScreen.getLastScreen()));
	}
}
