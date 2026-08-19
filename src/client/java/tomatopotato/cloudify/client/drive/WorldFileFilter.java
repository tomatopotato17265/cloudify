package tomatopotato.cloudify.client.drive;

import java.nio.file.Path;
import java.util.Set;

public class WorldFileFilter {
	private static final Set<String> EXCLUDED_FILE_NAMES = Set.of("session.lock", "level.dat_old");
	private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(".tmp", ".mca_tmp");

	public static boolean isExcluded(Path normalizedWorldRoot, Path candidate) {
		String fileName = candidate.getFileName().toString();
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
