package com.aio.platform.repo;

import com.aio.platform.domain.EnvStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvStatusRepo extends JpaRepository<EnvStatus, Long> {
    List<EnvStatus> findByProjectId(Long projectId);

    Optional<EnvStatus> findByProjectIdAndEnvironment(Long projectId, String environment);
}
