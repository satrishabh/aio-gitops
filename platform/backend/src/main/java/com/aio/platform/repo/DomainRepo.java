package com.aio.platform.repo;

import com.aio.platform.domain.Domain;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRepo extends JpaRepository<Domain, Long> {
    List<Domain> findByProjectIdOrderByHostAscPathAsc(Long projectId);

    List<Domain> findByProjectIdAndEnvOrderByHostAscPathAsc(Long projectId, String env);

    List<Domain> findByHostOrderByIdAsc(String host);

    Optional<Domain> findByHostAndPath(String host, String path);

    List<Domain> findByStatusIn(Collection<String> statuses);
}
