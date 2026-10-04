package org.openbased.job;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, String> {

    List<Job> findByStatusIn(Collection<JobStatus> statuses);

    boolean existsByTypeAndTargetIdAndStatusIn(String type, String targetId, Collection<JobStatus> statuses);
}
