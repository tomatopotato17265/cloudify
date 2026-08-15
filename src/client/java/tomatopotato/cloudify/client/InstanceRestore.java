package tomatopotato.cloudify.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveAuth;

public class InstanceRestore {
	public static void apply(Path gameDir, Path stagingDir) throws IOException {
		Path normalizedGameDir = gameDir.toAbsolutePath().normalize();
		Path protectedRelativePath = resolveProtectedRelativePath(normalizedGameDir);

		mergeDirectory(gameDir, stagingDir, null, protectedRelativePath);

		try {
			deleteRecursively(stagingDir);
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to clean up restore staging directory '{}'", stagingDir, e);
		}
	}

	private static Path resolveProtectedRelativePath(Path normalizedGameDir) {
		try {
			return normalizedGameDir.relativize(GoogleDriveAuth.TOKENS_DIRECTORY.toAbsolutePath().normalize());
		} catch (IllegalArgumentException e) {
			// The live Google Drive login data doesn't live under the game directory at all, so nothing in a
			// downloaded backup could correspond to it anyway (it would never have been uploaded in the first place).
			return Path.of("");
		}
	}

	private static void mergeDirectory(Path targetDir, Path stagedDir, Path relativePath, Path protectedRelativePath) throws IOException {
		Files.createDirectories(targetDir);

		try (var children = Files.list(stagedDir)) {
			for (Path staged : (Iterable<Path>) children::iterator) {
				String name = staged.getFileName().toString();
				Path childRelative = relativePath == null ? Path.of(name) : relativePath.resolve(name);

				if (childRelative.equals(protectedRelativePath)) {
					Cloudify.LOGGER.info("Skipping restore of '{}': protected (live Google Drive login data)", childRelative);
					continue;
				}

				Path target = targetDir.resolve(name);
				if (Files.isDirectory(staged) && isAncestorOf(childRelative, protectedRelativePath)) {
					mergeDirectory(target, staged, childRelative, protectedRelativePath);
				} else {
					deleteRecursively(target);
					Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static boolean isAncestorOf(Path candidateAncestor, Path path) {
		return path.startsWith(candidateAncestor) && !path.equals(candidateAncestor);
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}

		try (var paths = Files.walk(path)) {
			List<Path> sorted = paths.sorted(Comparator.reverseOrder()).toList();
			for (Path p : sorted) {
				Files.deleteIfExists(p);
			}
		}
	}
}
