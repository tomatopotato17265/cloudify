package tomatopotato.cloudify.drive;

public interface TransferProgressListener {
	void onProgress(long bytesTransferred, long totalBytes, int filesTransferred, int totalFiles, String currentFileName);

	TransferProgressListener NO_OP = (bytesTransferred, totalBytes, filesTransferred, totalFiles, currentFileName) -> {
	};
}
