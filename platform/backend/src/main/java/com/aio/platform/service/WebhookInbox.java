package com.aio.platform.service;

import com.aio.platform.domain.WebhookDelivery;
import com.aio.platform.repo.WebhookDeliveryRepo;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Deduplicates GitHub webhook deliveries by {@code X-GitHub-Delivery} (inbox / idempotent consumer pattern). */
@Service
public class WebhookInbox {
    static final Duration RETENTION = Duration.ofDays(30);
    private final WebhookDeliveryRepo deliveries;
    private volatile Instant lastPrune = Instant.EPOCH;

    public WebhookInbox(WebhookDeliveryRepo deliveries) {
        this.deliveries = deliveries;
    }

    /** True the first time a delivery id is seen; false for a redelivery that was already accepted. */
    public boolean firstTime(String deliveryId, Long projectId, String event) {
        pruneOccasionally();
        WebhookDelivery d = new WebhookDelivery();
        d.deliveryId = deliveryId;
        d.projectId = projectId;
        d.event = event;
        try {
            deliveries.saveAndFlush(d);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }

    public void outcome(String deliveryId, String outcome) {
        deliveries.findById(deliveryId).ifPresent(d -> {
            d.outcome = outcome == null ? null : (outcome.length() > 300 ? outcome.substring(0, 300) : outcome);
            deliveries.save(d);
        });
    }

    /** Processing failed: forget the delivery so GitHub's (or the buffer's) retry is processed, not dropped. */
    public void forget(String deliveryId) {
        deliveries.deleteById(deliveryId);
    }

    /** Keeps the inbox small: drop deliveries older than {@link #RETENTION}, at most every 6 hours. */
    void pruneOccasionally() {
        Instant now = Instant.now();
        if (Duration.between(lastPrune, now).compareTo(Duration.ofHours(6)) < 0) return;
        lastPrune = now;
        deliveries.deleteOlderThan(now.minus(RETENTION));
    }
}
