package com.aio.platform.repo;

import com.aio.platform.domain.ScanStep;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanStepRepo extends JpaRepository<ScanStep, Long> {
    List<ScanStep> findByRunIdOrderById(Long runId);

    Optional<ScanStep> findByRunIdAndTaskNameAndStepName(Long runId, String taskName, String stepName);
}
