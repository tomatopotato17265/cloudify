package tomatopotato.cloudify.client.drive;

import java.nio.file.Path;
import java.util.Set;

public class InstanceFileFilter {
	private static final Set<String> EXCLUDED_TOP_LEVEL_DIRS = Set.of("logs", "crash-reports");

	public static boolean isExcluded(Path gameDir, Path candidate) {
		Path normalizedGameDir = gameDir.toAbsolutePath().normalize();
		Path normalizedCandidate = candidate.toAbsolutePath().normalize();

		Path normalizedTokensDirectory = GoogleDriveAuth.TOKENS_DIRECTORY.toAbsolutePath().normalize();
		if (normalizedCandidate.equals(normalizedTokensDirectory) || normalizedCandidate.startsWith(normalizedTokensDirectory)) {
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
