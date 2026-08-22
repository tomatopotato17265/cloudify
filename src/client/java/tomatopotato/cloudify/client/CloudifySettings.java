package tomatopotato.cloudify.client;

import com.google.api.client.json.GenericJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.drive.GoogleDriveAuth;

public class CloudifySettings {
	private static final Path SETTINGS_FILE = GoogleDriveAuth.TOKENS_DIRECTORY.resolve("settings.json");

	public enum AutoSyncMode {
		OFF,
		AFTER_LAUNCH,
		BEFORE_SHUTDOWN
	}

	public record CloudifySettingsData(AutoSyncMode autoSyncMode) {
		public static final CloudifySettingsData DEFAULT = new CloudifySettingsData(AutoSyncMode.BEFORE_SHUTDOWN);
	}

	public static CloudifySettingsData load() {
		if (!Files.isRegularFile(SETTINGS_FILE)) {
			return CloudifySettingsData.DEFAULT;
		}

		try (InputStream in = Files.newInputStream(SETTINGS_FILE)) {
			GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);
			String autoSyncMode = (String) json.get("autoSyncMode");
			if (autoSyncMode == null) {
				return CloudifySettingsData.DEFAULT;
			}
			return new CloudifySettingsData(AutoSyncMode.valueOf(autoSyncMode));
		} catch (IOException | IllegalArgumentException e) {
			Cloudify.LOGGER.warn("Failed to read Cloudify settings, using defaults", e);
			return CloudifySettingsData.DEFAULT;
		}
	}

	public static void save(CloudifySettingsData data) {
		try {
			Files.createDirectories(SETTINGS_FILE.getParent());
			GenericJson json = new GenericJson();
			json.set("autoSyncMode", data.autoSyncMode().name());
			Files.write(SETTINGS_FILE, GoogleDriveAuth.JSON_FACTORY.toByteArray(json));
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to save Cloudify settings", e);
		}
	}
}
