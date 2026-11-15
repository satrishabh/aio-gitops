package com.aio.platform.repo;

import com.aio.platform.domain.WebhookDelivery;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WebhookDeliveryRepo extends JpaRepository<WebhookDelivery, String> {
    @Transactional
    @Modifying
    @Query("delete from WebhookDelivery d where d.receivedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
