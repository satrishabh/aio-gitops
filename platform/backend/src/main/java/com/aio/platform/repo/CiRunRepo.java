package com.aio.platform.repo;

import com.aio.platform.domain.CiRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CiRunRepo extends JpaRepository<CiRun, Long> {
    @org.springframework.data.jpa.repository.Query("select r.id from CiRun r where r.deliveryMode = 'INDEPENDENT' and r.status = 'SUCCEEDED' and r.deploy = true and r.releaseStatus in ('READY', 'RETRY_WAIT') and (r.releaseNextAttempt is null or r.releaseNextAttempt <= :now) order by r.id")
    List<Long> dueReleases(@org.springframework.data.repository.query.Param("now") java.time.Instant now, Pageable page);
    List<CiRun> findByExternalRegistryTrueAndRegistrySecretsCleanedFalseAndStatusIn(Collection<String> statuses, Pageable page);

    Optional<CiRun> findFirstByProjectIdAndDeliveryModeAndDeployTrueOrderByIdDesc(Long projectId, String deliveryMode);
    List<CiRun> findByProjectIdAndStatusIn(Long projectId, Collection<String> statuses);
    List<CiRun> findByProjectIdInOrderByIdDesc(Collection<Long> projectIds, Pageable page);
    List<CiRun> findByProjectIdInAndCreatedAtAfterOrderByIdAsc(Collection<Long> projectIds, java.time.Instant after);
    List<CiRun> findByStatusIn(Collection<String> statuses);

    List<CiRun> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    Optional<CiRun> findFirstByProjectIdOrderByIdDesc(Long projectId);

    Optional<CiRun> findByPipelineRunName(String pipelineRunName);

    List<CiRun> findByProjectIdAndCreatedAtAfterOrderByIdAsc(Long projectId, java.time.Instant after);

    List<CiRun> findByCreatedAtAfterOrderByIdAsc(java.time.Instant after);

    List<CiRun> findAllByOrderByIdDesc(Pageable page);
}
