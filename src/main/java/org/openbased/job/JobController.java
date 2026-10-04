package org.openbased.job;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs")
public class JobController {

    private final JobService jobs;
    private final AccessService access;

    public JobController(JobService jobs, AccessService access) {
        this.jobs = jobs;
        this.access = access;
    }

    public record JobResponse(String id, String type, JobStatus status, int progress, String message,
            Instant createdAt, Instant startedAt, Instant finishedAt) {
        static JobResponse of(Job j) {
            return new JobResponse(j.getId(), j.getType(), j.getStatus(), j.getProgress(), j.getMessage(),
                    j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt());
        }
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Get a background job; visible to the user who started it and to server.read holders")
    public JobResponse get(@PathVariable String jobId) {
        return JobResponse.of(visible(jobId));
    }

    @PostMapping("/{jobId}/cancel")
    @Operation(summary = "Cancel a queued or running job")
    public JobResponse cancel(@PathVariable String jobId) {
        Job job = visible(jobId);
        boolean owner = access.principal().userId() != null && access.principal().userId().equals(job.getOwnerId());
        if (!owner) {
            access.require(Scopes.SERVER_ADMIN);
        }
        return JobResponse.of(jobs.cancel(job.getId()));
    }

    private Job visible(String jobId) {
        Job job = jobs.find(jobId).orElse(null);
        String userId = access.principal().userId();
        boolean owner = job != null && userId != null && userId.equals(job.getOwnerId());
        if (job == null || !(owner || access.has(Scopes.SERVER_READ))) {
            throw ApiException.notFound("JOB_NOT_FOUND", "The requested job does not exist.");
        }
        return job;
    }
}
