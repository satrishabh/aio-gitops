package com.aio.platform.repo;

import com.aio.platform.domain.CiRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CiRunRepo extends JpaRepository<CiRun, Long> {
    List<CiRun> findByStatusIn(Collection<String> statuses);

    List<CiRun> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    Optional<CiRun> findFirstByProjectIdOrderByIdDesc(Long projectId);

    Optional<CiRun> findByPipelineRunName(String pipelineRunName);

    List<CiRun> findByProjectIdAndCreatedAtAfterOrderByIdAsc(Long projectId, java.time.Instant after);

    List<CiRun> findByCreatedAtAfterOrderByIdAsc(java.time.Instant after);
}
