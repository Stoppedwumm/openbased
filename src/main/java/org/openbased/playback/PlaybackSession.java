package org.openbased.playback;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** An active playback. Sessions live in memory and end on DELETE, idle timeout or restart. */
public final class PlaybackSession {

    private final String id;
    private final String userId;
    private final String mediaId;
    private final String fileId;
    private final PlaybackPlan plan;
    private final String deviceName;
    private final String platform;
    private final Instant createdAt = Instant.now();
    private volatile Instant lastActivity = Instant.now();
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();

    PlaybackSession(String id, String userId, String mediaId, String fileId, PlaybackPlan plan, String deviceName,
            String platform) {
        this.id = id;
        this.userId = userId;
        this.mediaId = mediaId;
        this.fileId = fileId;
        this.plan = plan;
        this.deviceName = deviceName;
        this.platform = platform;
    }

    public String id() { return id; }
    public String userId() { return userId; }
    public String mediaId() { return mediaId; }
    public String fileId() { return fileId; }
    public PlaybackPlan plan() { return plan; }
    public String deviceName() { return deviceName; }
    public String platform() { return platform; }
    public Instant createdAt() { return createdAt; }
    public Instant lastActivity() { return lastActivity; }

    void touch() {
        lastActivity = Instant.now();
    }

    void track(Process process) {
        processes.add(process);
        process.onExit().thenRun(() -> processes.remove(process));
    }

    void stopProcesses() {
        processes.forEach(Process::destroyForcibly);
        processes.clear();
    }
}
