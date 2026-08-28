package tomatopotato.cloudify.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.server.drive.GoogleDriveDeviceAuth;
import tomatopotato.cloudify.server.drive.GoogleDriveDeviceAuth.DeviceCode;
import tomatopotato.cloudify.server.drive.GoogleDriveDeviceAuth.PollResult;
import tomatopotato.cloudify.server.drive.GoogleDriveServerSync;
import tomatopotato.cloudify.server.drive.GoogleDriveServerSync.LastBackupResult;

public class CloudifyServerCommands {
	private static final AtomicBoolean LOGIN_IN_PROGRESS = new AtomicBoolean(false);
	private static final ScheduledExecutorService POLL_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "cloudify-device-auth-poll");
		thread.setDaemon(true);
		return thread;
	});

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("cloudify")
				.requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
				.then(Commands.literal("login").executes(CloudifyServerCommands::login))
				.then(Commands.literal("logout").executes(CloudifyServerCommands::logout))
				.then(Commands.literal("status").executes(CloudifyServerCommands::status))
		);
	}

	private static int login(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();

		if (!LOGIN_IN_PROGRESS.compareAndSet(false, true)) {
			source.sendFailure(Component.literal("A Cloudify Google Drive login is already in progress"));
			return 0;
		}

		try {
			if (GoogleDriveDeviceAuth.isLoggedIn()) {
				source.sendFailure(Component.literal("Already logged in to Google Drive. Run /cloudify logout first."));
				LOGIN_IN_PROGRESS.set(false);
				return 0;
			}

			DeviceCode deviceCode = GoogleDriveDeviceAuth.requestDeviceCode();
			source.sendSuccess(
				() -> Component.literal("To sign in to Google Drive, visit " + deviceCode.verificationUrl() + " and enter the code: " + deviceCode.userCode()),
				true
			);
			Cloudify.LOGGER.info("Cloudify Google Drive sign-in: visit {} and enter code {}", deviceCode.verificationUrl(), deviceCode.userCode());

			pollForLogin(deviceCode, System.currentTimeMillis() + deviceCode.expiresInSeconds() * 1000L, deviceCode.intervalSeconds());
			return 1;
		} catch (IOException e) {
			Cloudify.LOGGER.error("Failed to start Google Drive device sign-in", e);
			source.sendFailure(Component.literal("Failed to start Google Drive sign-in: " + e.getMessage()));
			LOGIN_IN_PROGRESS.set(false);
			return 0;
		}
	}

	private static void pollForLogin(DeviceCode deviceCode, long deadlineMillis, int intervalSeconds) {
		POLL_EXECUTOR.schedule(() -> {
			if (System.currentTimeMillis() >= deadlineMillis) {
				Cloudify.LOGGER.warn("Cloudify Google Drive sign-in expired before the code was entered");
				LOGIN_IN_PROGRESS.set(false);
				return;
			}

			try {
				PollResult result = GoogleDriveDeviceAuth.pollForCredential(deviceCode.deviceCode());
				switch (result.status()) {
					case SUCCESS -> {
						Cloudify.LOGGER.info("Cloudify signed in to Google Drive as {}", GoogleDriveDeviceAuth.getLoggedInEmail());
						LOGIN_IN_PROGRESS.set(false);
					}
					case DENIED -> {
						Cloudify.LOGGER.warn("Cloudify Google Drive sign-in was denied or failed");
						LOGIN_IN_PROGRESS.set(false);
					}
					case SLOW_DOWN -> pollForLogin(deviceCode, deadlineMillis, intervalSeconds + 5);
					case PENDING -> pollForLogin(deviceCode, deadlineMillis, intervalSeconds);
				}
			} catch (IOException e) {
				Cloudify.LOGGER.error("Failed to poll for Google Drive sign-in", e);
				LOGIN_IN_PROGRESS.set(false);
			}
		}, intervalSeconds, TimeUnit.SECONDS);
	}

	private static int logout(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		try {
			GoogleDriveDeviceAuth.logout();
			source.sendSuccess(() -> Component.literal("Logged out of Google Drive"), true);
			return 1;
		} catch (IOException e) {
			Cloudify.LOGGER.error("Failed to log out of Google Drive", e);
			source.sendFailure(Component.literal("Failed to log out: " + e.getMessage()));
			return 0;
		}
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		try {
			if (!GoogleDriveDeviceAuth.isLoggedIn()) {
				source.sendSuccess(() -> Component.literal("Not logged in to Google Drive"), false);
				return 1;
			}

			String email = GoogleDriveDeviceAuth.getLoggedInEmail();
			StringBuilder message = new StringBuilder("Logged in to Google Drive as ").append(email);

			LastBackupResult last = GoogleDriveServerSync.getLastBackupResult();
			if (last == null) {
				message.append("\nNo backup has run yet this session");
			} else {
				message.append("\nLast backup: '")
					.append(last.serverName())
					.append("' as '")
					.append(last.backupFolderName())
					.append("' (")
					.append(last.uploadedCount())
					.append(" changed, ")
					.append(last.deletedCount())
					.append(" gone, ")
					.append(last.unchangedCount())
					.append(" unchanged, ")
					.append(last.skippedCount())
					.append(" skipped, ")
					.append(last.totalBytes())
					.append(" bytes) in ")
					.append(last.durationMillis())
					.append(" ms");
			}

			String finalMessage = message.toString();
			source.sendSuccess(() -> Component.literal(finalMessage), false);
			return 1;
		} catch (IOException e) {
			Cloudify.LOGGER.error("Failed to check Google Drive login status", e);
			source.sendFailure(Component.literal("Failed to check status: " + e.getMessage()));
			return 0;
		}
	}
}
