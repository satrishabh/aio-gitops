package com.aio.platform.repo;

import com.aio.platform.domain.RunStep;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunStepRepo extends JpaRepository<RunStep, Long> {
    List<RunStep> findByRunIdOrderById(Long runId);

    Optional<RunStep> findByRunIdAndTaskNameAndStepName(Long runId, String taskName, String stepName);
}
