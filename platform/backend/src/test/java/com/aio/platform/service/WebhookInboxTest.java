package com.aio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aio.platform.domain.WebhookDelivery;
import com.aio.platform.repo.WebhookDeliveryRepo;
import com.aio.platform.service.WebhookInbox.Claim;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs each repository call in its own transaction, as in production (no test-wide transaction). */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "logging.level.org.hibernate.SQL=OFF", "spring.datasource.url=jdbc:h2:mem:webhookinbox;MODE=PostgreSQL"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookInboxTest {
    private static final byte[] PUSH = "{\"ref\":\"refs/heads/main\",\"after\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "{\"ref\":\"refs/heads/main\",\"after\":\"def\"}".getBytes(StandardCharsets.UTF_8);

    @Autowired WebhookDeliveryRepo deliveries;
    WebhookInbox inbox;

    @BeforeEach
    void setUp() {
        deliveries.deleteAll();
        inbox = new WebhookInbox(deliveries, Duration.ofMinutes(2), Duration.ofDays(14));
    }

    @Test
    void firstDeliveryIsProcessedAndAReplayGetsTheRecordedReply() {
        Claim first = inbox.claim(1L, "guid-1", "push", PUSH);
        assertThat(first).isInstanceOf(Claim.Fresh.class);
        inbox.complete(((Claim.Fresh) first).delivery(), 202, "queued", 42L);

        Claim replay = inbox.claim(1L, "guid-1", "push", PUSH);
        assertThat(replay).isEqualTo(new Claim.Replay(202, "queued"));
        WebhookDelivery stored = deliveries.findByProjectIdAndDeliveryId(1L, "guid-1").orElseThrow();
        assertThat(stored.status).isEqualTo(WebhookDelivery.DONE);
        assertThat(stored.runId).isEqualTo(42L);
        assertThat(stored.attempts).isEqualTo(1);
    }

    @Test
    void aReplayWhileTheFirstAttemptRunsIsBusy() {
        assertThat(inbox.claim(1L, "guid-2", "push", PUSH)).isInstanceOf(Claim.Fresh.class);
        assertThat(inbox.claim(1L, "guid-2", "push", PUSH)).isInstanceOf(Claim.Busy.class);
    }

    @Test
    void aStaleClaimIsTakenOverOnceAndOnlyOnce() {
        Claim.Fresh first = (Claim.Fresh) inbox.claim(1L, "guid-3", "push", PUSH);
        WebhookDelivery d = first.delivery();
        d.claimedAt = Instant.now().minus(Duration.ofMinutes(5)); // the first attempt's pod stopped
        deliveries.saveAndFlush(d);

        Claim takeover = inbox.claim(1L, "guid-3", "push", PUSH);
        assertThat(takeover).isInstanceOf(Claim.Fresh.class);
        assertThat(((Claim.Fresh) takeover).delivery().attempts).isEqualTo(2);
        // the takeover renewed the claim, so a concurrent replay does not take it again
        assertThat(inbox.claim(1L, "guid-3", "push", PUSH)).isInstanceOf(Claim.Busy.class);
    }

    @Test
    void aTakeoverIsFencedByTheAttemptCount() {
        Claim.Fresh first = (Claim.Fresh) inbox.claim(1L, "guid-4", "push", PUSH);
        WebhookDelivery d = first.delivery();
        Instant old = Instant.now().minus(Duration.ofMinutes(5));
        d.claimedAt = old;
        deliveries.saveAndFlush(d);
        // two replays read attempts=1; only the first conditional update wins
        assertThat(deliveries.takeOver(d.id, 1, Instant.now().minus(Duration.ofMinutes(2)), Instant.now())).isEqualTo(1);
        assertThat(deliveries.takeOver(d.id, 1, Instant.now().plusSeconds(1), Instant.now())).isZero();
    }

    @Test
    void theSameIdWithADifferentPayloadIsAConflict() {
        assertThat(inbox.claim(1L, "guid-5", "push", PUSH)).isInstanceOf(Claim.Fresh.class);
        assertThat(inbox.claim(1L, "guid-5", "push", OTHER)).isInstanceOf(Claim.Conflict.class);
    }

    @Test
    void theSameIdOnAnotherProjectIsSeparate() {
        assertThat(inbox.claim(1L, "guid-6", "push", PUSH)).isInstanceOf(Claim.Fresh.class);
        assertThat(inbox.claim(2L, "guid-6", "push", PUSH)).isInstanceOf(Claim.Fresh.class);
    }

    @Test
    void aReleasedClaimCanBeProcessedAgain() {
        Claim.Fresh first = (Claim.Fresh) inbox.claim(1L, "guid-7", "push", PUSH);
        inbox.release(first.delivery());
        assertThat(inbox.claim(1L, "guid-7", "push", PUSH)).isInstanceOf(Claim.Fresh.class);
    }

    @Test
    void missingOrOddDeliveryIdsAreNotTracked() {
        assertThat(inbox.claim(1L, null, "push", PUSH)).isInstanceOf(Claim.Untracked.class);
        assertThat(inbox.claim(1L, "", "push", PUSH)).isInstanceOf(Claim.Untracked.class);
        assertThat(inbox.claim(1L, "has spaces; drop table", "push", PUSH)).isInstanceOf(Claim.Untracked.class);
        assertThat(inbox.claim(1L, "x".repeat(101), "push", PUSH)).isInstanceOf(Claim.Untracked.class);
        assertThat(deliveries.count()).isZero();
    }

    @Test
    void longRepliesAreTruncatedAndOldDeliveriesPurged() {
        Claim.Fresh fresh = (Claim.Fresh) inbox.claim(1L, "guid-8", "push", PUSH);
        inbox.complete(fresh.delivery(), 200, "y".repeat(500), null);
        assertThat(deliveries.findByProjectIdAndDeliveryId(1L, "guid-8").orElseThrow().response).hasSize(200);

        WebhookDelivery old = deliveries.findByProjectIdAndDeliveryId(1L, "guid-8").orElseThrow();
        old.receivedAt = Instant.now().minus(Duration.ofDays(15));
        deliveries.saveAndFlush(old);
        inbox.claim(1L, "guid-9", "push", PUSH);
        inbox.purgeOld();
        assertThat(deliveries.findAll()).extracting(w -> w.deliveryId).containsExactly("guid-9");
    }
}
