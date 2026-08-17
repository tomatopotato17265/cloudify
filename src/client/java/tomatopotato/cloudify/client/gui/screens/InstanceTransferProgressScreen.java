package tomatopotato.cloudify.client.gui.screens;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import tomatopotato.cloudify.client.drive.TransferProgressListener;

public class InstanceTransferProgressScreen extends Screen {
	public interface TransferOperation {
		CompletableFuture<Void> start(TransferProgressListener listener, AtomicBoolean cancelled);
	}

	private final Screen lastScreen;
	private final TransferOperation operation;
	private final Runnable onSuccess;
	private final Consumer<Throwable> onFailure;
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33);

	private StringWidget progressWidget;
	private boolean started;
	private int lastFilesTransferred = -1;

	public InstanceTransferProgressScreen(Component title, Screen lastScreen, TransferOperation operation, Runnable onSuccess, Consumer<Throwable> onFailure) {
		super(title);
		this.lastScreen = lastScreen;
		this.operation = operation;
		this.onSuccess = onSuccess;
		this.onFailure = onFailure;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);

		LinearLayout content = this.layout.addToContents(LinearLayout.vertical()).spacing(8);
		content.defaultCellSetting().alignHorizontallyCenter();
		this.progressWidget = content.addChild(new StringWidget(Component.translatable("options.instance_transfer.starting"), this.font));

		this.layout.addToFooter(Button.builder(CommonComponents.GUI_CANCEL, button -> this.cancelled.set(true)).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();

		if (!this.started) {
			this.started = true;
			this.beginTransfer();
		}
	}

	private void beginTransfer() {
		this.operation.start(this::onProgress, this.cancelled).handleAsync((ignored, error) -> {
			if (this.minecraft.gui.screen() != this) {
				return null;
			}

			if (error != null) {
				this.onFailure.accept(error);
			} else if (this.cancelled.get()) {
				this.minecraft.gui.setScreen(this.lastScreen);
			} else {
				this.onSuccess.run();
			}
			return null;
		}, this.minecraft);
	}

	private void onProgress(long bytesTransferred, long totalBytes, int filesTransferred, int totalFiles, String currentFileName) {
		this.minecraft.execute(() -> {
			if (this.progressWidget == null || filesTransferred < this.lastFilesTransferred) {
				return;
			}
			this.lastFilesTransferred = filesTransferred;

			Component message = totalFiles > 0
				? Component.translatable(
					"options.instance_transfer.progress", filesTransferred, totalFiles, formatBytes(bytesTransferred), formatBytes(totalBytes)
				)
				: Component.translatable("options.instance_transfer.starting");
			this.progressWidget.setMessage(message);
		});
	}

	public static String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		double kilobytes = bytes / 1024.0;
		if (kilobytes < 1024) {
			return String.format("%.1f KB", kilobytes);
		}
		double megabytes = kilobytes / 1024.0;
		if (megabytes < 1024) {
			return String.format("%.1f MB", megabytes);
		}
		return String.format("%.2f GB", megabytes / 1024.0);
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void onClose() {
		this.cancelled.set(true);
	}
}
