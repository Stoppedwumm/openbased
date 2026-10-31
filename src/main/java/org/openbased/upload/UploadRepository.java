package org.openbased.upload;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadRepository extends JpaRepository<Upload, String> {

    List<Upload> findByStatusAndUpdatedAtBefore(UploadStatus status, Instant before);
}
