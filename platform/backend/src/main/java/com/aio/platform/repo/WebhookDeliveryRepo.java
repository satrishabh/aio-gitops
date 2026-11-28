package com.aio.platform.repo;

import com.aio.platform.domain.WebhookDelivery;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WebhookDeliveryRepo extends JpaRepository<WebhookDelivery, Long> {
    Optional<WebhookDelivery> findByProjectIdAndDeliveryId(Long projectId, String deliveryId);

    /**
     * Takes over a delivery whose first attempt stopped without recording a reply (e.g. the pod restarted).
     * Conditional on the attempt count that was read, so two concurrent replays cannot both take it over.
     * Returns 1 when taken.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update WebhookDelivery d set d.claimedAt = :now, d.attempts = d.attempts + 1
        where d.id = :id and d.status = 'PROCESSING' and d.attempts = :attempts and d.claimedAt < :staleBefore
        """)
    int takeOver(@Param("id") Long id, @Param("attempts") int attempts,
                 @Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("delete from WebhookDelivery d where d.receivedAt < :before")
    int deleteReceivedBefore(@Param("before") Instant before);
}
