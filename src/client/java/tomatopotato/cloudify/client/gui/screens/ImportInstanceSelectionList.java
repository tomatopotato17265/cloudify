package tomatopotato.cloudify.client.gui.screens;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import tomatopotato.cloudify.client.drive.GoogleDriveInstanceSync.DriveInstanceEntry;

public class ImportInstanceSelectionList extends ObjectSelectionList<ImportInstanceSelectionList.InstanceRowEntry> {
	private static final int ROW_WIDTH = 270;
	private static final int TEXT_COLOR = -8355712;

	private final Runnable onSelectionChanged;
	private @Nullable DriveInstanceEntry selectedEntry;

	public ImportInstanceSelectionList(Minecraft minecraft, int width, int height, List<DriveInstanceEntry> entries, Runnable onSelectionChanged) {
		super(minecraft, width, height, 0, 36);
		this.onSelectionChanged = onSelectionChanged;
		for (DriveInstanceEntry entry : entries) {
			this.addEntry(new InstanceRowEntry(entry));
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

	public @Nullable DriveInstanceEntry getSelectedEntry() {
		return this.selectedEntry;
	}

	private void select(DriveInstanceEntry entry) {
		this.selectedEntry = entry;
		this.onSelectionChanged.run();
	}

	public final class InstanceRowEntry extends ObjectSelectionList.Entry<InstanceRowEntry> {
		private final DriveInstanceEntry entry;
		private final StringWidget nameText;
		private final StringWidget infoText;
		private final StringWidget dateText;

		InstanceRowEntry(DriveInstanceEntry entry) {
			this.entry = entry;
			Minecraft minecraft = ImportInstanceSelectionList.this.minecraft;
			int maxTextWidth = ImportInstanceSelectionList.this.getRowWidth() - this.getTextX() - 2;

			Component nameComponent = Component.literal(entry.displayName());
			this.nameText = new StringWidget(nameComponent, minecraft.font);
			this.nameText.setMaxWidth(maxTextWidth);
			if (minecraft.font.width(nameComponent) > maxTextWidth) {
				this.nameText.setTooltip(Tooltip.create(nameComponent));
			}

			Component infoComponent = this.createInfoLine(entry);
			this.infoText = new StringWidget(infoComponent, minecraft.font);
			this.infoText.setMaxWidth(maxTextWidth);
			if (minecraft.font.width(infoComponent) > maxTextWidth) {
				this.infoText.setTooltip(Tooltip.create(infoComponent));
			}

			Component dateComponent = this.createDateLine(entry);
			this.dateText = new StringWidget(dateComponent, minecraft.font);
			this.dateText.setMaxWidth(maxTextWidth);
			if (minecraft.font.width(dateComponent) > maxTextWidth) {
				this.dateText.setTooltip(Tooltip.create(dateComponent));
			}
		}

		private Component createInfoLine(DriveInstanceEntry entry) {
			StringBuilder info = new StringBuilder();
			if (!entry.minecraftVersion().isEmpty()) {
				info.append(entry.minecraftVersion());
			}
			if (!entry.fabricLoaderVersion().isEmpty()) {
				if (!info.isEmpty()) {
					info.append(", ");
				}
				info.append("Fabric ").append(entry.fabricLoaderVersion());
			}
			if (!info.isEmpty()) {
				info.append(", ");
			}
			info.append(entry.modCount()).append(" mods, ").append(InstanceTransferProgressScreen.formatBytes(entry.totalSizeBytes()));
			return Component.literal(info.toString()).withColor(TEXT_COLOR);
		}

		private Component createDateLine(DriveInstanceEntry entry) {
			ZonedDateTime lastSyncedTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(entry.lastSyncedMillis()), ZoneId.systemDefault());
			return Component.literal(WorldSelectionList.DATE_FORMAT.format(lastSyncedTime)).withColor(TEXT_COLOR);
		}

		private int getTextX() {
			return this.getContentX() + 3;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			if (this.entry.equals(ImportInstanceSelectionList.this.selectedEntry)) {
				graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), -1);
				graphics.fill(this.getX() + 1, this.getY() + 1, this.getX() + this.getWidth() - 1, this.getY() + this.getHeight() - 1, -16777216);
			}

			int textX = this.getTextX();
			this.nameText.setPosition(textX, this.getContentY() + 1);
			this.nameText.extractRenderState(graphics, mouseX, mouseY, a);
			this.infoText.setPosition(textX, this.getContentY() + 9 + 3);
			this.infoText.extractRenderState(graphics, mouseX, mouseY, a);
			this.dateText.setPosition(textX, this.getContentY() + 9 + 9 + 3);
			this.dateText.extractRenderState(graphics, mouseX, mouseY, a);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			ImportInstanceSelectionList.this.select(this.entry);
			return true;
		}

		@Override
		public Component getNarration() {
			return Component.literal(this.entry.displayName());
		}
	}
}
