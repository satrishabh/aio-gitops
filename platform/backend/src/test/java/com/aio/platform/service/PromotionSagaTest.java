package com.aio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import com.aio.platform.saga.Saga;
import com.aio.platform.saga.Saga.Result;
import com.aio.platform.saga.SagaEngine;
import com.aio.platform.saga.SagaEventRepo;
import com.aio.platform.saga.SagaInstance;
import com.aio.platform.saga.SagaInstanceRepo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:promotionsaga;MODE=PostgreSQL"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PromotionSagaTest {
    @Autowired SagaInstanceRepo instances;
    @Autowired SagaEventRepo events;
    @Autowired ProjectRepo projects;
    @Autowired PromotionRepo promotions;
    @Autowired PlatformTransactionManager transactions;

    final GitOpsService gitops = mock(GitOpsService.class);
    final ArgoCdService argo = mock(ArgoCdService.class);
    final AuditService audit = mock(AuditService.class);
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T01:00:00Z"));
    SagaEngine engine;
    Project shop;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        instances.deleteAll();
        promotions.deleteAll();
        projects.deleteAll();
        Project p = new Project();
        p.name = "shop";
        p.imageRepo = "ghcr.io/acme/shop";
        shop = projects.save(p);
        PromotionSaga saga = new PromotionSaga(projects, promotions, gitops, argo, audit, true, Duration.ofMinutes(20));
        engine = new SagaEngine(instances, events, List.of(saga), transactions,
                new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(Clock.class));
        when(gitops.readTag(any(), eq("prod"))).thenReturn("v1");
    }

    @Test
    void approvedProdPromotionCommitsSyncsAndWaitsUntilHealthy() {
        when(argo.getApp(eq("shop-prod"), anyBoolean())).thenReturn(app("OutOfSync", "Progressing", "v1"));
        Promotion pr = start(pending("v2"));

        drain();
        verify(gitops).setTag(isShop(), eq("prod"), eq("v2"), contains("promote shop prod -> v2"));
        verify(argo).sync("shop-prod");
        assertThat(promotions.findById(pr.id).orElseThrow().status).isEqualTo(Promotion.APPLYING);

        when(argo.getApp(eq("shop-prod"), anyBoolean())).thenReturn(app("Synced", "Healthy", "v2"));
        clock.advance(Duration.ofSeconds(20));
        drain();

        Promotion done = promotions.findById(pr.id).orElseThrow();
        assertThat(done.status).isEqualTo(Promotion.DONE);
        assertThat(done.previousTag).isEqualTo("v1");
        verify(gitops, never()).setTag(any(), any(), eq("v1"), any());
    }

    @Test
    void degradedRolloutIsRevertedToThePreviousTag() {
        when(argo.getApp(eq("shop-prod"), anyBoolean())).thenReturn(app("Synced", "Degraded", "v2"));
        Promotion pr = start(pending("v2"));
        drain();
        for (int i = 0; i < 17; i++) {       // ~5.6 min of Degraded re-checks every 20 s
            clock.advance(Duration.ofSeconds(20));
            drain();
        }
        verify(gitops).setTag(isShop(), eq("prod"), eq("v1"), contains("revert shop prod -> v1"));
        verify(argo, times(2)).sync("shop-prod");                     // promote, then revert
        Promotion failed = promotions.findById(pr.id).orElseThrow();
        assertThat(failed.status).isEqualTo(Promotion.FAILED);
        assertThat(failed.message).startsWith("rolled back to v1").contains("Degraded");
    }

    @Test
    void failedCommitLeavesNothingToRevert() {
        doThrow(new IllegalStateException("409 conflict")).when(gitops).setTag(any(), any(), eq("v2"), any());
        Promotion pr = start(pending("v2"));
        for (int i = 0; i < 10; i++) {
            drain();
            clock.advance(Duration.ofMinutes(5));
        }
        Promotion failed = promotions.findById(pr.id).orElseThrow();
        assertThat(failed.status).isEqualTo(Promotion.FAILED);
        assertThat(failed.message).doesNotContain("rolled back").contains("409 conflict");
        verify(argo, never()).sync(any());
        verify(gitops, never()).setTag(any(), any(), eq("v1"), any());
    }

    @Test
    void onlyOneOfTwoConcurrentApprovalsClaimsTheRequest() {
        Promotion pr = promotions.save(pending("v2"));
        assertThat(promotions.claimPending(pr.id, "alice", clock.instant())).isEqualTo(1);
        assertThat(promotions.claimPending(pr.id, "bob", clock.instant())).isZero();
        assertThat(promotions.findById(pr.id).orElseThrow().decidedBy).isEqualTo("alice");
    }

    @Test
    void sagaStartIsIdempotentPerPromotion() {
        Promotion pr = promotions.save(pending("v2"));
        SagaInstance a = engine.start(PromotionSaga.TYPE, PromotionSaga.key(pr), PromotionSaga.data(shop, pr, "alice"));
        SagaInstance b = engine.start(PromotionSaga.TYPE, PromotionSaga.key(pr), PromotionSaga.data(shop, pr, "alice"));
        assertThat(b.id).isEqualTo(a.id);
    }

    @Test
    void evaluateRequiresTheNewImageNotJustHealthy() {
        Saga.Context c = Saga.Context.detached(Map.of(), clock.instant());
        assertThat(PromotionSaga.evaluate(app("Synced", "Healthy", "v1"), "ghcr.io/acme/shop", "v2", c))
                .isInstanceOf(Result.Wait.class);
        assertThat(PromotionSaga.evaluate(app("Synced", "Healthy", "v2"), "ghcr.io/acme/shop", "v2", c))
                .isInstanceOf(Result.Done.class);
    }

    @Test
    void digestTagsMatchByAtSign() {
        assertThat(PromotionSaga.runsImage(List.of("ghcr.io/acme/shop@sha256:abc"), "ghcr.io/acme/shop", "sha256:abc")).isTrue();
        assertThat(PromotionSaga.runsImage(List.of("ghcr.io/acme/shop:sha256:abc"), "ghcr.io/acme/shop", "sha256:abc")).isFalse();
        assertThat(PromotionSaga.runsImage(null, "ghcr.io/acme/shop", "v1")).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    /** The saga reloads the project from the DB, so match by id rather than instance. */
    private Project isShop() {
        Long id = shop.id;
        return argThat(p -> p != null && id.equals(p.id));
    }

    private Promotion pending(String tag) {
        Promotion pr = new Promotion();
        pr.projectId = shop.id;
        pr.fromEnv = "staging";
        pr.toEnv = "prod";
        pr.imageTag = tag;
        pr.kind = "PROMOTE";
        pr.status = Promotion.PENDING;
        pr.requestedBy = "alice";
        return pr;
    }

    /** What PromotionService.approve does once permissions are checked. */
    private Promotion start(Promotion pr) {
        pr = promotions.save(pr);
        assertThat(promotions.claimPending(pr.id, "bob", clock.instant())).isEqualTo(1);
        Promotion claimed = promotions.findById(pr.id).orElseThrow();
        claimed.sagaId = engine.start(PromotionSaga.TYPE, PromotionSaga.key(claimed), PromotionSaga.data(shop, claimed, "bob")).id;
        return promotions.save(claimed);
    }

    private void drain() {
        for (int i = 0; i < 50 && engine.runDue(10) > 0; i++) { }
    }

    private static Map<String, Object> app(String sync, String health, String tag) {
        Map<String, Object> status = new HashMap<>();
        status.put("sync", Map.of("status", sync));
        status.put("health", Map.of("status", health));
        status.put("summary", Map.of("images", List.of("ghcr.io/acme/shop:" + tag)));
        return Map.of("status", status);
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
