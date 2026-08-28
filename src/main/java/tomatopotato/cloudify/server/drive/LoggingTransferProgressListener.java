package tomatopotato.cloudify.server.drive;

import tomatopotato.cloudify.Cloudify;
import tomatopotato.cloudify.drive.TransferProgressListener;

public class LoggingTransferProgressListener implements TransferProgressListener {
	private static final long LOG_INTERVAL_MILLIS = 5000L;

	private final String serverName;
	private volatile long lastLoggedAtMillis = 0L;

	public LoggingTransferProgressListener(String serverName) {
		this.serverName = serverName;
	}

	@Override
	public void onProgress(long bytesTransferred, long totalBytes, int filesTransferred, int totalFiles, String currentFileName) {
		long now = System.currentTimeMillis();
		if (now - lastLoggedAtMillis < LOG_INTERVAL_MILLIS) {
			return;
		}
		lastLoggedAtMillis = now;

		Cloudify.LOGGER.info(
			"Server backup for '{}' progress: {}/{} files, {}/{} bytes", serverName, filesTransferred, totalFiles, bytesTransferred, totalBytes
		);
	}
}
