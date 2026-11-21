package com.aio.platform.repo;

import com.aio.platform.domain.AttachedService;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachedServiceRepo extends JpaRepository<AttachedService, Long> {
    List<AttachedService> findByProjectIdOrderByConnectorIdAscEnvAsc(Long projectId);

    Optional<AttachedService> findByProjectIdAndConnectorIdAndEnv(Long projectId, String connectorId, String env);

    List<AttachedService> findByConnectorIdAndStatusOrderByProjectIdAscEnvAsc(String connectorId, String status);
}
