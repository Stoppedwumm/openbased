package org.openbased.job;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Runs long operations (scans, metadata refreshes, upload processing) in the background. */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final ExecutorService executor;
    private final Map<String, Handle> running = new ConcurrentHashMap<>();

    public JobService(JobRepository jobs, OpenBasedProperties properties) {
        this.jobs = jobs;
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getJobs().getWorkers()), r -> {
            Thread t = new Thread(r, "openbased-job-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Work performed by a job. */
    @FunctionalInterface
    public interface Work {
        void run(Handle handle) throws Exception;
    }

    /** Lets running work report progress and observe cancellation. */
    public final class Handle {
        private final String jobId;
        private volatile boolean cancelled;
        private volatile int lastProgress = -1;

        Handle(String jobId) {
            this.jobId = jobId;
        }

        public String jobId() {
            return jobId;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void progress(int percent) {
            int p = Math.max(0, Math.min(100, percent));
            if (p != lastProgress) {
                lastProgress = p;
                update(jobId, job -> job.setProgress(p));
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void failInterruptedJobs() {
        for (Job job : jobs.findByStatusIn(EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING))) {
            job.setStatus(JobStatus.FAILED);
            job.setMessage("Interrupted by server restart");
            job.setFinishedAt(Instant.now());
            jobs.save(job);
        }
    }

    public boolean isActive(String type, String targetId) {
        return jobs.existsByTypeAndTargetIdAndStatusIn(type, targetId, EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING));
    }

    public Job submit(String type, String ownerId, String targetId, Work work) {
        Job job = new Job();
        job.setId(Ids.prefixed("job"));
        job.setType(type);
        job.setStatus(JobStatus.QUEUED);
        job.setOwnerId(ownerId);
        job.setTargetId(targetId);
        job.setCreatedAt(Instant.now());
        jobs.save(job);
        Handle handle = new Handle(job.getId());
        running.put(job.getId(), handle);
        executor.execute(() -> execute(job.getId(), handle, work));
        return job;
    }

    private void execute(String jobId, Handle handle, Work work) {
        try {
            if (handle.isCancelled()) {
                return;
            }
            update(jobId, job -> {
                job.setStatus(JobStatus.RUNNING);
                job.setStartedAt(Instant.now());
            });
            work.run(handle);
            update(jobId, job -> {
                job.setStatus(handle.isCancelled() ? JobStatus.CANCELLED : JobStatus.COMPLETED);
                if (!handle.isCancelled()) {
                    job.setProgress(100);
                }
                job.setFinishedAt(Instant.now());
            });
        } catch (Exception e) {
            log.warn("Job {} failed", jobId, e);
            update(jobId, job -> {
                job.setStatus(JobStatus.FAILED);
                job.setMessage(truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                job.setFinishedAt(Instant.now());
            });
        } finally {
            running.remove(jobId);
        }
    }

    public Optional<Job> find(String jobId) {
        return jobs.findById(jobId);
    }

    public Job cancel(String jobId) {
        Job job = jobs.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("JOB_NOT_FOUND", "The requested job does not exist."));
        if (job.getStatus().isFinished()) {
            throw ApiException.conflict("JOB_FINISHED", "The job has already finished.");
        }
        Handle handle = running.get(jobId);
        if (handle != null) {
            handle.cancelled = true;
        }
        if (job.getStatus() == JobStatus.QUEUED) {
            update(jobId, j -> {
                j.setStatus(JobStatus.CANCELLED);
                j.setFinishedAt(Instant.now());
            });
        }
        return jobs.findById(jobId).orElseThrow();
    }

    private synchronized void update(String jobId, java.util.function.Consumer<Job> change) {
        jobs.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == JobStatus.CANCELLED && job.getFinishedAt() != null) {
                return;
            }
            change.accept(job);
            jobs.save(job);
        });
    }

    private static String truncate(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
