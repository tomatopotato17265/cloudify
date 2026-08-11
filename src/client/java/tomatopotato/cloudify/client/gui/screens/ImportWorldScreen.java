package tomatopotato.cloudify.client.gui.screens;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveLogin;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync.DriveWorldEntry;

public class ImportWorldScreen extends Screen {
	private enum State {
		NOT_LOGGED_IN,
		LOADING,
		LOADED,
		ERROR
	}

	private final Screen lastScreen;
	private final Runnable onImportComplete;
	private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);
	private State state = State.LOADING;
	private List<DriveWorldEntry> entries = List.of();
	private @Nullable ImportWorldSelectionList worldList;
	private Button importButton;

	public ImportWorldScreen(Screen lastScreen, Runnable onImportComplete) {
		super(Component.translatable("options.select_world.import_world.title"));
		this.lastScreen = lastScreen;
		this.onImportComplete = onImportComplete;
	}

	@Override
	protected void init() {
		boolean loggedIn;
		try {
			loggedIn = GoogleDriveLogin.isLoggedIn();
		} catch (IOException e) {
			Cloudify.LOGGER.error("Failed to check Google Drive login state", e);
			this.state = State.ERROR;
			this.rebuild();
			return;
		}

		if (!loggedIn) {
			this.state = State.NOT_LOGGED_IN;
			this.rebuild();
			return;
		}

		this.loadWorlds();
	}

	private void loadWorlds() {
		this.state = State.LOADING;
		this.rebuild();

		GoogleDriveWorldSync.listWorldsAsync()
			.thenCombine(this.loadLocalLastPlayedTimes(), ImportWorldScreen::filterUpToDateEntries)
			.handleAsync((result, error) -> {
				if (this.minecraft.gui.screen() != this) {
					return null;
				}

				if (error != null) {
					Cloudify.LOGGER.error("Failed to list Google Drive worlds", error);
					this.state = State.ERROR;
				} else {
					this.entries = result;
					this.state = State.LOADED;
				}

				this.rebuild();
				return null;
			}, this.minecraft);
	}

	private CompletableFuture<Map<String, Long>> loadLocalLastPlayedTimes() {
		return CompletableFuture.supplyAsync(() -> {
			Map<String, Long> lastPlayedByName = new HashMap<>();
			for (LevelStorageSource.LevelDirectory directory : this.minecraft.getLevelSource().findLevelCandidates()) {
				try {
					long lastPlayed = Files.getLastModifiedTime(directory.dataFile()).toMillis();
					String name = directory.path().getFileName().toString();
					lastPlayedByName.merge(name, lastPlayed, Math::max);
				} catch (IOException e) {
					Cloudify.LOGGER.warn("Failed to read level.dat modification time for '{}'", directory.path(), e);
				}
			}
			return lastPlayedByName;
		}, Util.backgroundExecutor());
	}

	private static List<DriveWorldEntry> filterUpToDateEntries(List<DriveWorldEntry> driveEntries, Map<String, Long> localLastPlayedByName) {
		return driveEntries.stream()
			.filter(entry -> {
				Long localLastPlayed = localLastPlayedByName.get(entry.name());
				return localLastPlayed == null || localLastPlayed < entry.lastPlayedMillis();
			})
			.toList();
	}

	private void rebuild() {
		this.clearWidgets();
		if (this.worldList != null) {
			this.worldList.clearEntries();
		}
		this.layout = new HeaderAndFooterLayout(this, 33);
		this.layout.addTitleHeader(this.title, this.font);
		this.worldList = null;

		switch (this.state) {
			case NOT_LOGGED_IN -> this.buildNotLoggedIn();
			case LOADING -> this.buildLoading();
			case ERROR -> this.buildError();
			case LOADED -> this.buildLoaded();
		}

		LinearLayout buttonRow = LinearLayout.horizontal().spacing(4);
		this.importButton = buttonRow.addChild(
			Button.builder(Component.translatable("options.select_world.import_world.import"), button -> this.startImport()).width(98).build()
		);
		this.importButton.active = this.worldList != null && !this.worldList.getCheckedEntries().isEmpty();
		buttonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(98).build());
		this.layout.addToFooter(buttonRow);

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();

		if (this.worldList != null) {
			this.worldList.updateSizeAndPosition(this.worldList.getWidth(), this.worldList.getHeight(), this.worldList.getX(), this.worldList.getY());
		}
	}

	private LinearLayout createMessageContent() {
		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(8));
		content.defaultCellSetting().alignHorizontallyCenter();
		return content;
	}

	private void buildNotLoggedIn() {
		LinearLayout content = this.createMessageContent();
		content.addChild(new StringWidget(Component.translatable("options.select_world.import_world.not_logged_in"), this.font));
		content.addChild(
			Button.builder(
					Component.translatable("options.select_world.import_world.log_in"),
					button -> this.minecraft.gui.setScreen(new CloudSyncingScreen(this))
				)
				.width(150)
				.build()
		);
	}

	private void buildLoading() {
		LinearLayout content = this.createMessageContent();
		content.addChild(new StringWidget(Component.translatable("options.select_world.import_world.loading"), this.font));
	}

	private void buildError() {
		LinearLayout content = this.createMessageContent();
		content.addChild(new StringWidget(Component.translatable("options.select_world.import_world.error"), this.font));
		content.addChild(
			Button.builder(Component.translatable("options.select_world.import_world.retry"), button -> this.loadWorlds()).width(150).build()
		);
	}

	private void buildLoaded() {
		if (this.entries.isEmpty()) {
			LinearLayout content = this.createMessageContent();
			content.addChild(new StringWidget(Component.translatable("options.select_world.import_world.empty"), this.font));
			return;
		}

		this.worldList = this.layout.addToContents(
			new ImportWorldSelectionList(this.minecraft, this.width, this.layout.getContentHeight(), this.entries, this::updateImportButtonState)
		);
	}

	private void updateImportButtonState() {
		this.importButton.active = this.worldList != null && !this.worldList.getCheckedEntries().isEmpty();
	}

	private void startImport() {
		if (this.worldList == null) {
			return;
		}

		Set<DriveWorldEntry> selected = this.worldList.getCheckedEntries();
		if (selected.isEmpty()) {
			return;
		}

		LevelStorageSource levelSource = this.minecraft.getLevelSource();
		List<DriveWorldEntry> overwriting = new ArrayList<>();
		for (DriveWorldEntry entry : selected) {
			if (Files.isDirectory(levelSource.getLevelPath(entry.name()))) {
				overwriting.add(entry);
			}
		}

		if (overwriting.isEmpty()) {
			this.beginImport(selected);
			return;
		}

		String names = overwriting.stream().map(DriveWorldEntry::name).collect(Collectors.joining(", "));
		this.minecraft.gui.setScreen(
			new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						this.beginImport(selected);
					} else {
						this.minecraft.gui.setScreen(this);
					}
				},
				Component.translatable("options.select_world.import_world.confirm_overwrite.title"),
				Component.translatable("options.select_world.import_world.confirm_overwrite.message", names)
			)
		);
	}

	private void beginImport(Set<DriveWorldEntry> selected) {
		this.minecraft.setScreenAndShow(new GenericMessageScreen(Component.translatable("options.select_world.import_world.importing")));
		this.runImportBatch(new ArrayList<>(selected), new ArrayList<>());
	}

	private void runImportBatch(List<DriveWorldEntry> remaining, List<String> failedNames) {
		if (remaining.isEmpty()) {
			this.finishImport(failedNames);
			return;
		}

		DriveWorldEntry entry = remaining.remove(0);
		this.importOne(entry).handleAsync((ignored, error) -> {
			if (error != null) {
				Cloudify.LOGGER.error("Failed to import world '{}' from Google Drive", entry.name(), error);
				failedNames.add(entry.name());
			}
			this.runImportBatch(remaining, failedNames);
			return null;
		}, this.minecraft);
	}

	private CompletableFuture<Void> importOne(DriveWorldEntry entry) {
		LevelStorageSource levelSource = this.minecraft.getLevelSource();
		Path savesRoot = levelSource.getBaseDir();
		Path targetDir = levelSource.getLevelPath(entry.name());

		return CompletableFuture.supplyAsync(() -> createStagingDir(savesRoot), Util.backgroundExecutor())
			.thenCompose(
				stagingDir -> GoogleDriveWorldSync.importWorldAsync(entry, stagingDir)
					.thenRunAsync(() -> finalizeImport(levelSource, entry.name(), stagingDir, targetDir), Util.backgroundExecutor())
					.whenComplete((ignored, error) -> {
						if (error != null) {
							deleteQuietly(stagingDir);
						}
					})
			);
	}

	private static Path createStagingDir(Path savesRoot) {
		try {
			return Files.createTempDirectory(savesRoot, "cloudify-import-");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void finalizeImport(LevelStorageSource levelSource, String worldName, Path stagingDir, Path targetDir) {
		try {
			if (Files.isDirectory(targetDir)) {
				try (LevelStorageSource.LevelStorageAccess access = levelSource.createAccess(worldName)) {
					access.deleteLevel();
				}
			}
			Files.move(stagingDir, targetDir);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void deleteQuietly(Path dir) {
		try (var paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException e) {
			Cloudify.LOGGER.warn("Failed to clean up staging directory '{}'", dir, e);
		}
	}

	private void finishImport(List<String> failedNames) {
		if (failedNames.isEmpty()) {
			this.onImportComplete.run();
			return;
		}

		String names = String.join(", ", failedNames);
		this.minecraft.gui.setScreen(
			new AlertScreen(
				this.onImportComplete,
				Component.translatable("options.select_world.import_world.failed.title"),
				Component.translatable("options.select_world.import_world.failed.message", names)
			)
		);
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}
}
