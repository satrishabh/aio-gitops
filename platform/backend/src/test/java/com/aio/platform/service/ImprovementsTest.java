package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Deployment;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImprovementsTest {

    private static final String PLACEHOLDER = """
            image:
              repository: ghcr.io/acme/shop
              tag: "0.0.0"
            replicaCount: 1
            ingress:
              enabled: true
              host: shop-dev.1.2.3.4.nip.io
            """;

    // ------------------------------------------------------------------ GitOps values

    @Test
    void normalizeAddsPullSecretAndParksPlaceholder() {
        String n = GitOpsService.normalize(PLACEHOLDER);
        assertTrue(n.contains("imagePullSecrets:\n  - name: ghcr-pull"));
        assertEquals(0, GitOpsService.extractReplicas(n));
        assertEquals(n, GitOpsService.normalize(n), "normalize must be idempotent");
    }

    @Test
    void normalizeKeepsReplicasForRealTags() {
        String real = GitOpsService.replaceTag(PLACEHOLDER, "abc123");
        assertEquals(1, GitOpsService.extractReplicas(GitOpsService.normalize(real)));
    }

    @Test
    void deployingScalesFromZeroButNotAboveOne() {
        String parked = GitOpsService.normalize(PLACEHOLDER);
        String deployed = GitOpsService.ensureRunning(GitOpsService.normalize(GitOpsService.replaceTag(parked, "abc123")));
        assertEquals(1, GitOpsService.extractReplicas(deployed));
        String three = GitOpsService.setReplicas(deployed, 3);
        assertEquals(3, GitOpsService.extractReplicas(GitOpsService.ensureRunning(three)));
    }

    @Test
    void parsesAppUrlOnlyWhenIngressEnabled() {
        assertEquals("http://shop-dev.1.2.3.4.nip.io", GitOpsService.parse(PLACEHOLDER).appUrl());
        assertNull(GitOpsService.parse(PLACEHOLDER.replace("enabled: true", "enabled: false")).appUrl());
        assertNull(GitOpsService.parse(PLACEHOLDER.replace("shop-dev.1.2.3.4.nip.io", "example.local")).appUrl());
    }

    // ------------------------------------------------------------------ pull secret

    @Test
    void dockerConfigJsonHasBasicAuth() {
        String json = ClusterService.dockerConfig("ghcr.io", "me", "tok");
        String auth = Base64.getEncoder().encodeToString("me:tok".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"auths\":{\"ghcr.io\":{\"auth\":\"" + auth + "\"}}}", json);
    }

    // ------------------------------------------------------------------ approvals / scan gate

    @Test
    void approvalRules() {
        assertFalse(PromotionService.canApprove(List.of(), "alice", "alice"), "four-eyes without APPROVERS");
        assertTrue(PromotionService.canApprove(List.of(), "bob", "alice"));
        assertTrue(PromotionService.canApprove(List.of("alice"), "alice", "alice"), "listed approver may self-approve");
        assertFalse(PromotionService.canApprove(List.of("alice"), "bob", "alice"));
    }

    @Test
    void severityValidation() {
        assertEquals("CRITICAL", ProjectService.validSeverity(" critical "));
        assertThrows(IllegalArgumentException.class, () -> ProjectService.validSeverity("LOW"));
        Project p = new Project();
        p.scanSeverity = "bogus";
        assertEquals("HIGH,CRITICAL", RunService.severityOf(p));
    }

    // ------------------------------------------------------------------ DORA

    @Test
    void doraMetrics() {
        Instant t0 = Instant.parse("2026-10-01T00:00:00Z");
        CiRun ok1 = run(1, CiRun.SUCCEEDED, t0, "ghcr.io/a/shop:aaa");
        CiRun bad = run(2, CiRun.FAILED, t0.plusSeconds(7200), null);
        CiRun ok2 = run(3, CiRun.SUCCEEDED, t0.plusSeconds(3 * 3600), "ghcr.io/a/shop:bbb");

        Deployment prod1 = dep("prod", "aaa", t0.plusSeconds(4 * 3600));
        Deployment prod2 = dep("prod", "bbb", t0.plusSeconds(5 * 3600));
        Deployment dev = dep("dev", "aaa", t0.plusSeconds(600));
        Deployment placeholder = dep("staging", "0.0.0", t0);

        Promotion rb = new Promotion();
        rb.projectId = 1L; rb.kind = "ROLLBACK"; rb.toEnv = "prod"; rb.status = Promotion.DONE;

        var m = MetricsService.compute(10, List.of(prod1, prod2, dev, placeholder), List.of(ok1, bad, ok2), List.of(rb));
        assertEquals(2, m.prodDeploys());
        assertEquals(1, m.devDeploys());
        assertEquals(0.2, m.prodDeploysPerDay());
        assertEquals(3.0, m.leadTimeHoursMedian());      // (4h + 2h) / 2
        assertEquals(0.5, m.changeFailureRate());        // 1 rollback / 2 prod deploys
        assertEquals(1.0, m.timeToRestoreHoursMean());   // failed at 2h -> green at 3h
        assertEquals(0.67, m.ciSuccessRate());
    }

    private static CiRun run(long id, String status, Instant created, String image) {
        CiRun r = new CiRun();
        r.id = id; r.projectId = 1L; r.status = status; r.deploy = true; r.imageUrl = image;
        r.createdAt = created; r.startedAt = created; r.finishedAt = created;
        return r;
    }

    private static Deployment dep(String env, String tag, Instant at) {
        Deployment d = new Deployment();
        d.projectId = 1L; d.environment = env; d.imageTag = tag; d.deployedAt = at;
        return d;
    }
}
