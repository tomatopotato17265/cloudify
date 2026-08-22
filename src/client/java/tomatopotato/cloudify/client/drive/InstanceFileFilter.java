package tomatopotato.cloudify.client.drive;

import java.nio.file.Path;
import java.util.Set;
import tomatopotato.cloudify.drive.GoogleDriveAuth;

public class InstanceFileFilter {
	private static final Set<String> EXCLUDED_TOP_LEVEL_DIRS = Set.of("logs", "crash-reports");
	private static final Path NORMALIZED_TOKENS_DIRECTORY = GoogleDriveAuth.TOKENS_DIRECTORY.toAbsolutePath().normalize();

	public static boolean isExcluded(Path normalizedGameDir, Path candidate) {
		Path normalizedCandidate = candidate.toAbsolutePath().normalize();

		if (normalizedCandidate.equals(NORMALIZED_TOKENS_DIRECTORY) || normalizedCandidate.startsWith(NORMALIZED_TOKENS_DIRECTORY)) {
			return true;
		}

		if (normalizedCandidate.equals(normalizedGameDir)) {
			return false;
		}

		Path relative = normalizedGameDir.relativize(normalizedCandidate);
		if (relative.getNameCount() == 0) {
			return false;
		}

		String topLevelName = relative.getName(0).toString();
		return EXCLUDED_TOP_LEVEL_DIRS.contains(topLevelName);
	}
}
