package org.openbased.download;

public enum DownloadStatus {
    /** Waiting for or running its preparation job. */
    PENDING,
    READY,
    FAILED,
    CANCELLED,
    /** The prepared file was deleted after {@code openbased.downloads.retention}. */
    EXPIRED
}
