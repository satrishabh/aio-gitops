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
        ok1.commitSha = "c1"; bad.commitSha = "c2"; ok2.commitSha = "c2";   // the fix re-ran the same commit

        Deployment prod1 = dep("prod", "aaa", t0.plusSeconds(4 * 3600));
        Deployment prod2 = dep("prod", "bbb", t0.plusSeconds(5 * 3600));
        Deployment dev = dep("dev", "aaa", t0.plusSeconds(600));
        Deployment placeholder = dep("staging", "0.0.0", t0);
        Deployment staging = dep("staging", "aaa", t0.plusSeconds(3600));

        Promotion rb = new Promotion();
        rb.projectId = 1L; rb.kind = "ROLLBACK"; rb.toEnv = "prod"; rb.status = Promotion.DONE;

        var m = MetricsService.compute(10, List.of(prod1, prod2, dev, placeholder, staging), List.of(ok1, bad, ok2), List.of(rb));
        assertEquals(2, m.prodDeploys());
        assertEquals(1, m.devDeploys());
        assertEquals(0.2, m.prodDeploysPerDay());
        assertEquals(3.0, m.leadTimeHoursMedian());      // (4h + 2h) / 2
        assertEquals(0.5, m.changeFailureRate());        // 1 rollback / 2 prod deploys
        assertEquals(1.0, m.timeToRestoreHoursMean());   // failed at 2h -> green at 3h
        assertEquals(0.67, m.ciSuccessRate());
        assertEquals(1, m.stagingDeploys());             // the 0.0.0 placeholder is not a deployment
        assertEquals(2, m.commitsBuilt());               // c1 and c2; c2 ran twice
    }

    @Test
    void dailySplitsDeploysPerEnvironment() {
        Instant today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds(60);
        var rows = MetricsService.daily(2, List.of(run(1, CiRun.SUCCEEDED, today, null)),
                List.of(dep("dev", "a", today), dep("staging", "a", today), dep("prod", "a", today),
                        dep("prod", "b", today), dep("staging", "0.0.0", today)));
        var last = rows.get(1);
        assertEquals(4L, last.get("deploys"));
        assertEquals(1L, last.get("deploysDev"));
        assertEquals(1L, last.get("deploysStaging"));
        assertEquals(2L, last.get("deploysProd"));
        assertEquals(1L, last.get("succeeded"));
        assertEquals(0L, rows.get(0).get("deploys"));
    }

    @Test
    void dailyCarriesDoraReadingsPerDay() {
        Instant day0 = java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant yesterday = day0.minusSeconds(86400);
        CiRun built = run(1, CiRun.SUCCEEDED, yesterday.plusSeconds(3600), "ghcr.io/a/shop:aaa");   // 01:00 yesterday
        CiRun broke = run(2, CiRun.FAILED, day0.plusSeconds(3600), null);                           // 01:00 today
        CiRun fixed = run(3, CiRun.SUCCEEDED, day0.plusSeconds(3 * 3600), null);                    // 03:00 today
        Deployment prod = dep("prod", "aaa", day0.plusSeconds(1800));                                // 00:30 today
        Promotion rb = new Promotion();
        rb.projectId = 1L; rb.kind = "ROLLBACK"; rb.toEnv = "prod"; rb.status = Promotion.DONE; rb.decidedAt = day0.plusSeconds(7200);

        var rows = MetricsService.daily(2, List.of(built, broke, fixed), List.of(prod), List.of(rb));
        var today = rows.get(1);
        assertEquals(23.5, today.get("leadTimeHours"));     // built 01:00 yesterday -> prod 00:30 today
        assertEquals(1L, today.get("rollbacks"));
        assertEquals(2.0, today.get("restoreHours"));       // red at 01:00 -> green at 03:00
        assertNull(rows.get(0).get("leadTimeHours"));        // no prod deploy yesterday
        assertNull(rows.get(0).get("restoreHours"));
        assertEquals(0L, rows.get(0).get("rollbacks"));
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
