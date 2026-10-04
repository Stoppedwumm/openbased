package org.openbased.job;

public enum JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
