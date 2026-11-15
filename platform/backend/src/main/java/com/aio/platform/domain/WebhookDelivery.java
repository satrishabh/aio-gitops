package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * Inbox entry for one GitHub webhook delivery (idempotent consumer). The primary key is GitHub's
 * {@code X-GitHub-Delivery} id, so a redelivery (manual, or by the webhook buffer after a timeout) is detected
 * and not processed twice. Always inserted, never merged: a duplicate key fails the insert.
 */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery implements Persistable<String> {
    @Id public String deliveryId;
    public Long projectId;
    public String event;
    public Instant receivedAt = Instant.now();
    @Column(length = 300) public String outcome;
    @Transient private boolean fresh = true;

    @Override public String getId() { return deliveryId; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void loaded() { fresh = false; }
}
