package tomatopotato.cloudify.server;

import com.google.api.client.json.GenericJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.drive.GoogleDriveAuth;

public class CloudifyServerSettings {
	private static final Path SETTINGS_FILE = GoogleDriveAuth.TOKENS_DIRECTORY.resolve("server").resolve("settings.json");
	private static final int DEFAULT_BACKUP_INTERVAL_MINUTES = 5;

	public record CloudifyServerSettingsData(int backupIntervalMinutes, String serverDisplayName) {
	}

	public static CloudifyServerSettingsData load() {
		String defaultServerDisplayName = defaultServerDisplayName();

		if (!Files.isRegularFile(SETTINGS_FILE)) {
			return new CloudifyServerSettingsData(DEFAULT_BACKUP_INTERVAL_MINUTES, defaultServerDisplayName);
		}

		try (InputStream in = Files.newInputStream(SETTINGS_FILE)) {
			GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);
			Number backupIntervalMinutes = (Number) json.get("backupIntervalMinutes");
			String serverDisplayName = (String) json.get("serverDisplayName");
			return new CloudifyServerSettingsData(
				backupIntervalMinutes != null ? backupIntervalMinutes.intValue() : DEFAULT_BACKUP_INTERVAL_MINUTES,
				serverDisplayName != null ? serverDisplayName : defaultServerDisplayName
			);
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to read Cloudify server settings, using defaults", e);
			return new CloudifyServerSettingsData(DEFAULT_BACKUP_INTERVAL_MINUTES, defaultServerDisplayName);
		}
	}

	public static void save(CloudifyServerSettingsData data) {
		try {
			Files.createDirectories(SETTINGS_FILE.getParent());
			GenericJson json = new GenericJson();
			json.set("backupIntervalMinutes", data.backupIntervalMinutes());
			json.set("serverDisplayName", data.serverDisplayName());
			Files.write(SETTINGS_FILE, GoogleDriveAuth.JSON_FACTORY.toByteArray(json));
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to save Cloudify server settings", e);
		}
	}

	private static String defaultServerDisplayName() {
		Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path fileName = gameDir.getFileName();
		return fileName != null ? fileName.toString() : "server";
	}
}
