package tomatopotato.cloudify.client.gui.screens;

import com.mojang.blaze3d.platform.NativeImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync;
import tomatopotato.cloudify.client.drive.GoogleDriveWorldSync.DriveWorldEntry;

public class ImportWorldSelectionList extends ObjectSelectionList<ImportWorldSelectionList.WorldRowEntry> {
	private static final int ROW_WIDTH = 270;
	private static final int ICON_SIZE = 32;
	private static final int TEXT_COLOR = -8355712;

	private final Set<DriveWorldEntry> checkedEntries = new LinkedHashSet<>();
	private final Runnable onSelectionChanged;

	public ImportWorldSelectionList(Minecraft minecraft, int width, int height, List<DriveWorldEntry> entries, Runnable onSelectionChanged) {
		super(minecraft, width, height, 0, 36);
		this.onSelectionChanged = onSelectionChanged;
		for (DriveWorldEntry entry : entries) {
			this.addEntry(new WorldRowEntry(entry));
		}
	}

	@Override
	public int getRowWidth() {
		return ROW_WIDTH;
	}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}

	@Override
	protected void clearEntries() {
		this.children().forEach(WorldRowEntry::close);
		super.clearEntries();
	}

	public Set<DriveWorldEntry> getCheckedEntries() {
		return Set.copyOf(this.checkedEntries);
	}

	private void toggleChecked(DriveWorldEntry entry) {
		if (!this.checkedEntries.remove(entry)) {
			this.checkedEntries.add(entry);
		}
		this.onSelectionChanged.run();
	}

	public final class WorldRowEntry extends ObjectSelectionList.Entry<WorldRowEntry> {
		private final DriveWorldEntry entry;
		private final StringWidget nameText;
		private final StringWidget locationText;
		private final StringWidget infoText;
		private final FaviconTexture icon;

		WorldRowEntry(DriveWorldEntry entry) {
			this.entry = entry;
			Minecraft minecraft = ImportWorldSelectionList.this.minecraft;
			int maxTextWidth = ImportWorldSelectionList.this.getRowWidth() - this.getTextX() - 2;

			Component nameComponent = Component.literal(entry.displayName());
			this.nameText = new StringWidget(nameComponent, minecraft.font);
			this.nameText.setMaxWidth(maxTextWidth);
			if (minecraft.font.width(nameComponent) > maxTextWidth) {
				this.nameText.setTooltip(Tooltip.create(nameComponent));
			}

			Component locationComponent = this.createLocationLine(entry);
			this.locationText = new StringWidget(locationComponent, minecraft.font);
			this.locationText.setMaxWidth(maxTextWidth);
			if (minecraft.font.width(locationComponent) > maxTextWidth) {
				this.locationText.setTooltip(Tooltip.create(locationComponent));
			}

			Component infoComponent = this.createInfoLine(entry);
			this.infoText = new StringWidget(infoComponent, minecraft.font);
			this.infoText.setMaxWidth(maxTextWidth);
			if (minecraft.font.width(infoComponent) > maxTextWidth) {
				this.infoText.setTooltip(Tooltip.create(infoComponent));
			}

			this.icon = FaviconTexture.forWorld(minecraft.getTextureManager(), entry.name());
			if (entry.iconFileId() != null) {
				Cloudify.LOGGER.info("Downloading icon for world '{}' (fileId={})", entry.name(), entry.iconFileId());
				GoogleDriveWorldSync.downloadIconAsync(entry.iconFileId()).handleAsync((bytes, error) -> {
					if (error != null) {
						Cloudify.LOGGER.warn("Failed to download icon for world '{}' from Google Drive", entry.name(), error);
						return null;
					}
					Cloudify.LOGGER.info("Downloaded icon for world '{}' ({} bytes)", entry.name(), bytes.length);
					if (!this.icon.isClosed()) {
						try {
							this.icon.upload(NativeImage.read(bytes));
							Cloudify.LOGGER.info("Uploaded icon texture for world '{}'", entry.name());
						} catch (Exception e) {
							Cloudify.LOGGER.warn("Failed to load icon for world '{}' from Google Drive", entry.name(), e);
						}
					} else {
						Cloudify.LOGGER.info("Skipped icon upload for world '{}': row already closed", entry.name());
					}
					return null;
				}, minecraft);
			} else {
				Cloudify.LOGGER.info("No iconFileId for world '{}', using default icon", entry.name());
			}
		}

		private Component createLocationLine(DriveWorldEntry entry) {
			ZonedDateTime lastPlayedTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(entry.lastPlayedMillis()), ZoneId.systemDefault());
			return Component.literal(entry.name() + " (" + WorldSelectionList.DATE_FORMAT.format(lastPlayedTime) + ")").withColor(TEXT_COLOR);
		}

		private Component createInfoLine(DriveWorldEntry entry) {
			if (entry.gameMode().isEmpty()) {
				return Component.translatable("options.select_world.import_world.unknown_info").withColor(TEXT_COLOR);
			}

			MutableComponent result = entry.hardcore()
				? Component.translatable("gameMode.hardcore")
				: Component.translatable("gameMode." + entry.gameMode());
			if (!entry.version().isEmpty()) {
				result.append(", ").append(Component.translatable("selectWorld.version")).append(CommonComponents.SPACE).append(entry.version());
			}
			return result.withColor(TEXT_COLOR);
		}

		private int getTextX() {
			return this.getContentX() + ICON_SIZE + 3;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			if (ImportWorldSelectionList.this.checkedEntries.contains(this.entry)) {
				graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), -1);
				graphics.fill(this.getX() + 1, this.getY() + 1, this.getX() + this.getWidth() - 1, this.getY() + this.getHeight() - 1, -16777216);
			}

			int textX = this.getTextX();
			this.nameText.setPosition(textX, this.getContentY() + 1);
			this.nameText.extractRenderState(graphics, mouseX, mouseY, a);
			this.locationText.setPosition(textX, this.getContentY() + 9 + 3);
			this.locationText.extractRenderState(graphics, mouseX, mouseY, a);
			this.infoText.setPosition(textX, this.getContentY() + 9 + 9 + 3);
			this.infoText.extractRenderState(graphics, mouseX, mouseY, a);

			graphics.blit(
				RenderPipelines.GUI_TEXTURED, this.icon.textureLocation(), this.getContentX(), this.getContentY(), 0.0F, 0.0F, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE
			);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			ImportWorldSelectionList.this.toggleChecked(this.entry);
			return true;
		}

		@Override
		public Component getNarration() {
			return Component.literal(this.entry.displayName());
		}

		void close() {
			if (!this.icon.isClosed()) {
				this.icon.close();
			}
		}
	}
}
