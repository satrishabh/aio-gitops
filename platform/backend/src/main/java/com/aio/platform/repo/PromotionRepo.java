package com.aio.platform.repo;

import com.aio.platform.domain.Promotion;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRepo extends JpaRepository<Promotion, Long> {
    @org.springframework.data.jpa.repository.Query("select p from Promotion p where p.projectId in :ids and (:status is null or p.status = :status) order by p.id desc")
    List<Promotion> findVisible(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids,
        @org.springframework.data.repository.query.Param("status") String status, Pageable page);
    List<Promotion> findByStatusOrderByIdDesc(String status);

    List<Promotion> findByRequestedAtAfterOrderByIdAsc(java.time.Instant after);

    List<Promotion> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    List<Promotion> findAllByOrderByIdDesc(Pageable page);

    /**
     * Atomically moves a PENDING request to APPLYING. Returns 0 when it was already decided, so two approvers
     * clicking at once cannot both apply it.
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Promotion p set p.status = 'APPLYING', p.decidedBy = :actor, "
            + "p.decidedAt = :at, p.version = p.version + 1 where p.id = :id and p.status = 'PENDING'")
    int claimPending(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("actor") String actor,
                     @org.springframework.data.repository.query.Param("at") java.time.Instant at);
}
