package org.openbased.download;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadRepository extends JpaRepository<Download, String> {

    Page<Download> findByUserId(String userId, Pageable pageable);

    List<Download> findByUserIdAndFileIdAndStatusIn(String userId, String fileId, Collection<DownloadStatus> statuses);

    long countByUserIdAndStatus(String userId, DownloadStatus status);

    List<Download> findByStatus(DownloadStatus status);

    List<Download> findByStatusAndExpiresAtBefore(DownloadStatus status, Instant cutoff);
}
