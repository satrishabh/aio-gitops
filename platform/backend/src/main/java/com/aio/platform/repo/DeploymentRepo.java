package com.aio.platform.repo;

import com.aio.platform.domain.Deployment;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentRepo extends JpaRepository<Deployment, Long> {
    List<Deployment> findByProjectIdOrderByDeployedAtDesc(Long projectId, Pageable page);

    boolean existsByProjectIdAndEnvironmentAndArgoHistoryId(Long projectId, String environment, Long argoHistoryId);

    List<Deployment> findByDeployedAtAfter(java.time.Instant after);

    List<Deployment> findByProjectIdAndDeployedAtAfter(Long projectId, java.time.Instant after);
}
