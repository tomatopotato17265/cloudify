package tomatopotato.cloudify.server.drive;

import java.nio.file.Path;
import java.util.Set;
import tomatopotato.cloudify.drive.GoogleDriveAuth;

public class ServerFileFilter {
	private static final Set<String> EXCLUDED_TOP_LEVEL_DIRS = Set.of("logs", "crash-reports");
	private static final Set<String> EXCLUDED_FILE_NAMES = Set.of("session.lock", "level.dat_old");
	private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(".tmp", ".mca_tmp");
	private static final Path NORMALIZED_TOKENS_DIRECTORY = GoogleDriveAuth.TOKENS_DIRECTORY.toAbsolutePath().normalize();

	public static boolean isExcluded(Path normalizedServerRoot, Path candidate) {
		Path normalizedCandidate = candidate.toAbsolutePath().normalize();

		if (normalizedCandidate.equals(NORMALIZED_TOKENS_DIRECTORY) || normalizedCandidate.startsWith(NORMALIZED_TOKENS_DIRECTORY)) {
			return true;
		}

		if (!normalizedCandidate.equals(normalizedServerRoot)) {
			Path relative = normalizedServerRoot.relativize(normalizedCandidate);
			if (relative.getNameCount() > 0 && EXCLUDED_TOP_LEVEL_DIRS.contains(relative.getName(0).toString())) {
				return true;
			}
		}

		String fileName = normalizedCandidate.getFileName().toString();
		if (EXCLUDED_FILE_NAMES.contains(fileName)) {
			return true;
		}

		for (String extension : EXCLUDED_EXTENSIONS) {
			if (fileName.endsWith(extension)) {
				return true;
			}
		}

		return false;
	}
}
