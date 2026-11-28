package com.aio.platform.service;

import com.aio.platform.domain.WebhookDelivery;
import com.aio.platform.repo.WebhookDeliveryRepo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * De-duplicates GitHub webhook deliveries by their X-GitHub-Delivery id, per project.
 *
 * <p>The Azure webhook buffer replays a delivery when AIO answered 5xx, 429 or not within its 5 s forward
 * timeout, and GitHub's "Redeliver" re-sends the same id. Without this inbox each replay could start another
 * build and dev deployment. The first attempt claims the id; later attempts get the recorded reply:
 * <ul>
 *   <li>same id, same payload, reply recorded: {@link Claim.Replay} with the original status and body;</li>
 *   <li>same id, first attempt still running: {@link Claim.Busy}; the controller answers 429 so the buffer retries;</li>
 *   <li>same id, first attempt stopped without a reply for longer than {@code stale-after}: taken over and run again;</li>
 *   <li>same id, different payload: {@link Claim.Conflict} (409), never processed.</li>
 * </ul>
 * A first attempt that throws releases its claim, so a later redelivery is processed normally.
 * Delivery is at-least-once: a pod that stops after starting the build but before recording the reply can, after
 * {@code stale-after}, lead to one more build on a replay. That window is a few milliseconds.
 */
@Service
public class WebhookInbox {
    private static final Logger log = LoggerFactory.getLogger(WebhookInbox.class);
    /** GitHub sends a GUID; accept any short opaque id and fall back to no de-duplication otherwise. */
    private static final Pattern DELIVERY_ID = Pattern.compile("[A-Za-z0-9-]{1,100}");
    private static final int MAX_RESPONSE = 200;

    private final WebhookDeliveryRepo deliveries;
    private final Duration staleAfter;
    private final Duration retention;

    public WebhookInbox(WebhookDeliveryRepo deliveries,
                        @Value("${app.webhooks.stale-after:PT2M}") Duration staleAfter,
                        @Value("${app.webhooks.inbox-retention:P14D}") Duration retention) {
        this.deliveries = deliveries;
        this.staleAfter = staleAfter;
        this.retention = retention;
    }

    public sealed interface Claim {
        /** This attempt owns the delivery: process it, then {@link #complete} or {@link #release}. */
        record Fresh(WebhookDelivery delivery) implements Claim {}
        /** Already handled: answer with the recorded reply. */
        record Replay(int status, String body) implements Claim {}
        /** Another attempt is processing it right now. */
        record Busy() implements Claim {}
        /** The id was already used for a different payload. */
        record Conflict() implements Claim {}
        /** No usable delivery id: process without de-duplication (previous behaviour). */
        record Untracked() implements Claim {}
    }

    public Claim claim(Long projectId, String deliveryId, String event, byte[] body) {
        if (deliveryId == null || !DELIVERY_ID.matcher(deliveryId).matches()) return new Claim.Untracked();
        String hash = sha256(body);
        // Replays are the common duplicate: answer them from a read. The insert below still settles races.
        var seen = deliveries.findByProjectIdAndDeliveryId(projectId, deliveryId);
        if (seen.isPresent()) return existing(seen.get(), hash);
        WebhookDelivery d = new WebhookDelivery();
        d.projectId = projectId;
        d.deliveryId = deliveryId;
        d.event = event == null ? null : truncate(event, 50);
        d.payloadSha256 = hash;
        try {
            return new Claim.Fresh(deliveries.saveAndFlush(d));
        } catch (DataIntegrityViolationException concurrent) {
            // another attempt inserted it between our read and insert; if it was released since, the retry claims it
            return deliveries.findByProjectIdAndDeliveryId(projectId, deliveryId)
                    .map(other -> existing(other, hash)).orElseGet(Claim.Busy::new);
        }
    }

    private Claim existing(WebhookDelivery seen, String hash) {
        Long projectId = seen.projectId;
        String deliveryId = seen.deliveryId;
        if (!hash.equals(seen.payloadSha256)) {
            log.warn("webhook delivery {} for project {} reused with a different payload; rejected", deliveryId, projectId);
            return new Claim.Conflict();
        }
        if (WebhookDelivery.DONE.equals(seen.status)) {
            log.info("webhook delivery {} for project {} already handled ({}); replaying the reply",
                    deliveryId, projectId, seen.httpStatus);
            return new Claim.Replay(seen.httpStatus == null ? 200 : seen.httpStatus, seen.response == null ? "" : seen.response);
        }
        Instant now = Instant.now();
        if (deliveries.takeOver(seen.id, seen.attempts, now.minus(staleAfter), now) == 1) {
            log.warn("webhook delivery {} for project {}: earlier attempt stopped without a reply; processing again",
                    deliveryId, projectId);
            return new Claim.Fresh(deliveries.findById(seen.id).orElseThrow());
        }
        return new Claim.Busy();
    }

    /** Records the reply for replays. */
    public void complete(WebhookDelivery d, int status, String body, Long runId) {
        d.status = WebhookDelivery.DONE;
        d.httpStatus = status;
        d.response = body == null ? null : truncate(body, MAX_RESPONSE);
        d.runId = runId;
        d.completedAt = Instant.now();
        deliveries.save(d);
    }

    /** The attempt failed before replying; forget the claim so a redelivery is processed. */
    public void release(WebhookDelivery d) {
        try {
            deliveries.deleteById(d.id);
        } catch (RuntimeException e) {
            log.warn("could not release webhook delivery {} ({}); it becomes retryable after {}",
                    d.deliveryId, e.getClass().getSimpleName(), staleAfter);
        }
    }

    // AIO sleeps when idle and stops at night, so run some time after each start rather than at a fixed hour.
    @Scheduled(initialDelayString = "${app.webhooks.inbox-cleanup-initial-ms:600000}",
               fixedDelayString = "${app.webhooks.inbox-cleanup-ms:21600000}")
    public void purgeOld() {
        int removed = deliveries.deleteReceivedBefore(Instant.now().minus(retention));
        if (removed > 0) log.info("removed {} webhook deliveries older than {}", removed, retention);
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
