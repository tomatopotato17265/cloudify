package tomatopotato.cloudify.client.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.FileContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tomatopotato.cloudify.Cloudify;

public class GoogleDriveWorldSync {
	private static final String DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	private static final String DRIVE_FOLDER_NAME = "Cloudify";
	private static final String ZIP_EXTENSION = ".zip";

	public record DriveWorldEntry(String fileId, String name, long sizeBytes, Instant modifiedTime) {
	}

	public static void uploadWorld(Path worldFolder, String worldName) {
		try {
			if (!GoogleDriveLogin.isLoggedIn()) {
				return;
			}

			Credential credential = GoogleDriveLogin.getCredential();
			Drive drive = new Drive.Builder(GoogleDriveAuth.HTTP_TRANSPORT, GoogleDriveAuth.JSON_FACTORY, credential).setApplicationName("Cloudify").build();

			Path zipFile = zipWorldFolder(worldFolder, worldName);
			try {
				String folderId = findOrCreateFolder(drive);
				uploadZip(drive, folderId, worldName + ".zip", zipFile);
				Cloudify.LOGGER.info("Uploaded world '{}' to Google Drive", worldName);
			} finally {
				Files.deleteIfExists(zipFile);
			}
		} catch (IOException e) {
			Cloudify.LOGGER.error("Failed to upload world '{}' to Google Drive", worldName, e);
		}
	}

	public static List<DriveWorldEntry> listWorlds() throws IOException {
		if (!GoogleDriveLogin.isLoggedIn()) {
			return List.of();
		}

		Credential credential = GoogleDriveLogin.getCredential();
		Drive drive = new Drive.Builder(GoogleDriveAuth.HTTP_TRANSPORT, GoogleDriveAuth.JSON_FACTORY, credential).setApplicationName("Cloudify").build();

		Optional<String> folderId = findFolder(drive);
		if (folderId.isEmpty()) {
			return List.of();
		}

		String query = "'" + folderId.get() + "' in parents and trashed = false";
		FileList result = drive.files()
			.list()
			.setQ(query)
			.setSpaces("drive")
			.setFields("files(id, name, size, modifiedTime)")
			.setPageSize(1000)
			.execute();
		List<File> files = result.getFiles();
		if (files == null) {
			return List.of();
		}

		return files.stream().map(GoogleDriveWorldSync::toDriveWorldEntry).toList();
	}

	private static DriveWorldEntry toDriveWorldEntry(File file) {
		String name = file.getName();
		if (name.endsWith(ZIP_EXTENSION)) {
			name = name.substring(0, name.length() - ZIP_EXTENSION.length());
		}
		long sizeBytes = file.getSize() != null ? file.getSize() : 0L;
		Instant modifiedTime = Instant.ofEpochMilli(file.getModifiedTime().getValue());
		return new DriveWorldEntry(file.getId(), name, sizeBytes, modifiedTime);
	}

	private static Path zipWorldFolder(Path worldFolder, String worldName) throws IOException {
		Path zipFile = Files.createTempFile("cloudify-" + worldName, ".zip");
		try (OutputStream fileOut = Files.newOutputStream(zipFile); ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
			try (var paths = Files.walk(worldFolder)) {
				for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
					zipOut.putNextEntry(new ZipEntry(worldFolder.relativize(path).toString().replace('\\', '/')));
					Files.copy(path, zipOut);
					zipOut.closeEntry();
				}
			}
		}
		return zipFile;
	}

	private static String findOrCreateFolder(Drive drive) throws IOException {
		Optional<String> existingFolderId = findFolder(drive);
		if (existingFolderId.isPresent()) {
			return existingFolderId.get();
		}

		File folderMetadata = new File().setName(DRIVE_FOLDER_NAME).setMimeType(DRIVE_FOLDER_MIME_TYPE);
		File folder = drive.files().create(folderMetadata).setFields("id").execute();
		return folder.getId();
	}

	private static Optional<String> findFolder(Drive drive) throws IOException {
		String query = "mimeType = '" + DRIVE_FOLDER_MIME_TYPE + "' and name = '" + DRIVE_FOLDER_NAME + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();
		if (matches == null || matches.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(matches.get(0).getId());
	}

	private static void uploadZip(Drive drive, String folderId, String fileName, Path zipFile) throws IOException {
		String query = "'" + folderId + "' in parents and name = '" + fileName + "' and trashed = false";
		FileList result = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute();
		List<File> matches = result.getFiles();

		FileContent mediaContent = new FileContent("application/zip", zipFile.toFile());
		if (matches != null && !matches.isEmpty()) {
			drive.files().update(matches.get(0).getId(), new File(), mediaContent).execute();
		} else {
			File metadata = new File().setName(fileName).setParents(List.of(folderId));
			drive.files().create(metadata, mediaContent).execute();
		}
	}
}
