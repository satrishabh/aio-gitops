package com.aio.platform.saga;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaInstanceRepo extends JpaRepository<SagaInstance, Long> {
    Optional<SagaInstance> findByBusinessKey(String businessKey);

    /** Open sagas (active, or paused with an intervention deadline) that are due and not leased, oldest due first. */
    @Query("select s.id from SagaInstance s where s.status in ('RUNNING','COMPENSATING','PAUSED') "
            + "and (s.nextAttemptAt is null or s.nextAttemptAt <= :now) "
            + "and (s.lockedUntil is null or s.lockedUntil <= :now) order by s.nextAttemptAt")
    List<Long> due(@Param("now") Instant now, Pageable page);

    /** Earliest moment any open saga needs the worker; null when nothing is open. */
    @Query("select min(s.nextAttemptAt) from SagaInstance s where s.status in ('RUNNING','COMPENSATING','PAUSED')")
    Instant nextDue();

    long countByStatusIn(Collection<String> statuses);
}
