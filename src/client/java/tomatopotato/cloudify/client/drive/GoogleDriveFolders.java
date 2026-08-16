package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

public class GoogleDriveFolders {
	public static final String DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	public static final String DRIVE_FOLDER_NAME = "Cloudify";
	public static final String WORLDS_FOLDER_NAME = "Worlds";
	public static final String INSTANCES_FOLDER_NAME = "Instances";
	// Reserved for future server backups; not created or used yet.
	public static final String SERVERS_FOLDER_NAME = "Servers";
	public static final long MULTIPART_MAX_BYTES = 5L * 1024 * 1024;
	private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();

	public static void countRequest() {
		REQUEST_COUNT.incrementAndGet();
	}

	public static void resetRequestCount() {
		REQUEST_COUNT.set(0);
	}

	public static int getRequestCount() {
		return REQUEST_COUNT.get();
	}

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
		countRequest();
		File folder = drive.files().create(folderMetadata).setFields("id").execute();
		return folder.getId();
	}

	public static Optional<String> findFolder(Drive drive, String name, @Nullable String parentId) throws IOException {
		StringBuilder query = new StringBuilder("mimeType = '").append(DRIVE_FOLDER_MIME_TYPE).append("' and name = '").append(name).append("' and trashed = false");
		if (parentId != null) {
			query.append(" and '").append(parentId).append("' in parents");
		}

		countRequest();
		FileList result = drive.files().list().setQ(query.toString()).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();
		if (matches == null || matches.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(matches.get(0).getId());
	}

	public static String uploadFile(Drive drive, String folderId, String fileName, AbstractInputStreamContent mediaContent) throws IOException {
		String query = "'" + folderId + "' in parents and name = '" + fileName + "' and trashed = false";
		countRequest();
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();

		if (matches != null && !matches.isEmpty()) {
			return updateFile(drive, matches.get(0).getId(), mediaContent);
		} else {
			return createFile(drive, folderId, fileName, mediaContent);
		}
	}

	private static String createFile(Drive drive, String folderId, String fileName, AbstractInputStreamContent mediaContent) throws IOException {
		File metadata = new File().setName(fileName).setParents(List.of(folderId));
		countRequest();
		Drive.Files.Create request = drive.files().create(metadata, mediaContent).setFields("id");
		configureUpload(request.getMediaHttpUploader(), mediaContent.getLength());
		File created = request.execute();
		return created.getId();
	}

	private static String updateFile(Drive drive, String fileId, AbstractInputStreamContent mediaContent) throws IOException {
		countRequest();
		Drive.Files.Update request = drive.files().update(fileId, new File(), mediaContent);
		configureUpload(request.getMediaHttpUploader(), mediaContent.getLength());
		request.execute();
		return fileId;
	}

	private static void configureUpload(MediaHttpUploader uploader, long length) {
		uploader.setDirectUploadEnabled(length >= 0 && length <= MULTIPART_MAX_BYTES);
	}

	public static void trashRecursively(Drive drive, String fileId) throws IOException {
		String query = "'" + fileId + "' in parents and trashed = false";
		countRequest();
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id, mimeType)").setPageSize(1000).execute();
		List<File> children = result.getFiles();
		if (children != null) {
			for (File child : children) {
				if (DRIVE_FOLDER_MIME_TYPE.equals(child.getMimeType())) {
					trashRecursively(drive, child.getId());
				} else {
					countRequest();
					drive.files().update(child.getId(), new File().setTrashed(true)).execute();
				}
			}
		}

		countRequest();
		drive.files().update(fileId, new File().setTrashed(true)).execute();
	}
}
