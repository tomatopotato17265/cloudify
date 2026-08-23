package tomatopotato.cloudify.server.drive;

import com.google.api.services.drive.Drive;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import tomatopotato.cloudify.drive.GoogleDriveFolders;

import static tomatopotato.cloudify.drive.GoogleDriveFolders.DRIVE_FOLDER_NAME;
import static tomatopotato.cloudify.drive.GoogleDriveFolders.SERVERS_FOLDER_NAME;

public class GoogleDriveServerSync {
	public static String resolveServerFolder(Drive drive, String serverName) throws IOException {
		String slug = GoogleDriveFolders.slugify(serverName, "server");
		ServerDriveIdCache.Data cache = ServerDriveIdCache.load();

		String cloudifyFolderId = cache.cloudifyFolderId();
		String serversFolderId = cache.serversFolderId();
		String serverFolderId = cache.serverFolderIds().get(slug);

		boolean cacheUsable = cloudifyFolderId != null && serversFolderId != null && serverFolderId != null && GoogleDriveFolders.isFolderUsable(drive, serverFolderId);
		if (cacheUsable) {
			return serverFolderId;
		}

		cloudifyFolderId = GoogleDriveFolders.findOrCreateFolder(drive, DRIVE_FOLDER_NAME, null);
		serversFolderId = GoogleDriveFolders.findOrCreateFolder(drive, SERVERS_FOLDER_NAME, cloudifyFolderId);
		serverFolderId = GoogleDriveFolders.findOrCreateFolder(drive, slug, serversFolderId);

		Map<String, String> updatedServerFolderIds = new LinkedHashMap<>(cache.serverFolderIds());
		updatedServerFolderIds.put(slug, serverFolderId);
		ServerDriveIdCache.save(new ServerDriveIdCache.Data(cloudifyFolderId, serversFolderId, updatedServerFolderIds));

		return serverFolderId;
	}
}
