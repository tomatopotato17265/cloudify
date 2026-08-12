package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public class GoogleDriveFolders {
	public static final String DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	public static final String DRIVE_FOLDER_NAME = "Cloudify";

	public static Drive buildDriveClient(Credential credential) {
		return new Drive.Builder(GoogleDriveAuth.HTTP_TRANSPORT, GoogleDriveAuth.JSON_FACTORY, credential).setApplicationName("Cloudify").build();
	}

	public static String findOrCreateFolder(Drive drive, String name, @Nullable String parentId) throws IOException {
		Optional<String> existingFolderId = findFolder(drive, name, parentId);
		if (existingFolderId.isPresent()) {
			return existingFolderId.get();
		}

		File folderMetadata = new File().setName(name).setMimeType(DRIVE_FOLDER_MIME_TYPE);
		if (parentId != null) {
			folderMetadata.setParents(List.of(parentId));
		}
		File folder = drive.files().create(folderMetadata).setFields("id").execute();
		return folder.getId();
	}

	public static Optional<String> findFolder(Drive drive, String name, @Nullable String parentId) throws IOException {
		StringBuilder query = new StringBuilder("mimeType = '").append(DRIVE_FOLDER_MIME_TYPE).append("' and name = '").append(name).append("' and trashed = false");
		if (parentId != null) {
			query.append(" and '").append(parentId).append("' in parents");
		}

		FileList result = drive.files().list().setQ(query.toString()).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();
		if (matches == null || matches.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(matches.get(0).getId());
	}

	public static void uploadFile(Drive drive, String folderId, String fileName, AbstractInputStreamContent mediaContent) throws IOException {
		String query = "'" + folderId + "' in parents and name = '" + fileName + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();

		if (matches != null && !matches.isEmpty()) {
			drive.files().update(matches.get(0).getId(), new File(), mediaContent).execute();
		} else {
			File metadata = new File().setName(fileName).setParents(List.of(folderId));
			drive.files().create(metadata, mediaContent).execute();
		}
	}
}
