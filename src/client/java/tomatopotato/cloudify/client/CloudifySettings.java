package tomatopotato.cloudify.client;

import com.google.api.client.json.GenericJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveAuth;

public class CloudifySettings {
	private static final Path SETTINGS_FILE = GoogleDriveAuth.TOKENS_DIRECTORY.resolve("settings.json");

	public record CloudifySettingsData(boolean autoSyncInstance) {
		public static final CloudifySettingsData DEFAULT = new CloudifySettingsData(false);
	}

	public static CloudifySettingsData load() {
		if (!Files.isRegularFile(SETTINGS_FILE)) {
			return CloudifySettingsData.DEFAULT;
		}

		try (InputStream in = Files.newInputStream(SETTINGS_FILE)) {
			GenericJson json = GoogleDriveAuth.JSON_FACTORY.fromInputStream(in, GenericJson.class);
			Boolean autoSyncInstance = (Boolean) json.get("autoSyncInstance");
			return new CloudifySettingsData(autoSyncInstance != null && autoSyncInstance);
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to read Cloudify settings, using defaults", e);
			return CloudifySettingsData.DEFAULT;
		}
	}

	public static void save(CloudifySettingsData data) {
		try {
			Files.createDirectories(SETTINGS_FILE.getParent());
			GenericJson json = new GenericJson();
			json.set("autoSyncInstance", data.autoSyncInstance());
			Files.write(SETTINGS_FILE, GoogleDriveAuth.JSON_FACTORY.toByteArray(json));
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to save Cloudify settings", e);
		}
	}
}
