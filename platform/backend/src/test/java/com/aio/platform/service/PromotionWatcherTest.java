package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromotionWatcherTest {
    static final String REPO = "ghcr.io/satrishabh/user-profile-manager";
    static final String TAG = "d5cd7e4d7180";
    static final String APP = "user-profile-manager-staging";

    PromotionRepo promotions = mock(PromotionRepo.class);
    ProjectRepo projects = mock(ProjectRepo.class);
    ArgoCdService argo = mock(ArgoCdService.class);
    GitOpsService gitops = mock(GitOpsService.class);
    AuditService audit = mock(AuditService.class);
    PromotionWatcher watcher = new PromotionWatcher(promotions, projects, argo, gitops, audit);
    Instant start = Instant.parse("2026-11-21T10:00:00Z");
    Promotion pr = new Promotion();

    @BeforeEach
    void setUp() {
        Project p = new Project();
        p.id = 7L;
        p.name = "user-profile-manager";
        p.imageRepo = REPO;
        when(projects.findById(7L)).thenReturn(Optional.of(p));
        when(gitops.readValuesCached(p, "staging")).thenReturn(new GitOpsService.EnvValues(TAG, 1, null));
        pr.id = 3L;
        pr.projectId = 7L;
        pr.fromEnv = "dev";
        pr.toEnv = "staging";
        pr.imageTag = TAG;
        pr.kind = "PROMOTE";
        pr.status = Promotion.DEPLOYING;
        pr.deployingSince = start;
    }

    static Map<String, Object> app(String sync, String health, String... images) {
        return Map.of("status", Map.of("sync", Map.of("status", sync), "health", Map.of("status", health),
                "summary", Map.of("images", List.of(images))));
    }

    @Test
    void healthyWithTheNewImageIsDone() {
        when(argo.getApp(APP, false)).thenReturn(app("Synced", "Healthy", REPO + ":" + TAG));
        watcher.watch(pr, start.plusSeconds(90));
        assertEquals(Promotion.DONE, pr.status);
        verify(promotions).save(pr);
        verify(audit).record(eq("argocd"), eq("promotion.healthy"), eq("user-profile-manager/staging"), anyString());
    }

    @Test
    void healthyButStillTheOldImageKeepsWaiting() {
        when(argo.getApp(APP, false)).thenReturn(app("Synced", "Healthy", REPO + ":oldtag"));
        watcher.watch(pr, start.plusSeconds(20));
        assertEquals(Promotion.DEPLOYING, pr.status);
        verify(promotions, never()).save(any());
    }

    @Test
    void crashLoopOfTheNewPodFailsWithReasonAndBoundedLogs() {
        when(argo.getApp(APP, false)).thenReturn(app("Synced", "Progressing", REPO + ":oldtag"));
        when(argo.resourceTree(APP)).thenReturn(Map.of("nodes", List.of(
                Map.of("kind", "Pod", "name", "old-pod", "namespace", "staging", "images", List.of(REPO + ":oldtag"),
                        "health", Map.of("status", "Healthy")),
                Map.of("kind", "Pod", "name", "upm-abc", "namespace", "staging", "images", List.of(REPO + ":" + TAG),
                        "health", Map.of("status", "Degraded", "message", "back-off restarting failed container")))));
        when(argo.resource(eq(APP), any())).thenReturn(Map.of("status", Map.of("containerStatuses", List.of(Map.of(
                "name", "app", "ready", false, "restartCount", 4,
                "state", Map.of("waiting", Map.of("reason", "CrashLoopBackOff", "message", "back-off 1m20s restarting failed container=app")),
                "lastState", Map.of("terminated", Map.of("exitCode", 1, "reason", "Error")))))));
        String longLog = IntStream.rangeClosed(1, 60).mapToObj(i -> "line " + i + " " + "x".repeat(150)).collect(Collectors.joining("\n"))
                + "\nERROR: KC_DB_URL is required";
        when(argo.logs(APP, "staging", "upm-abc", "app", true, 20)).thenReturn(longLog);

        watcher.watch(pr, start.plusSeconds(75));

        assertEquals(Promotion.FAILED, pr.status);
        assertTrue(pr.message.startsWith("CrashLoopBackOff: back-off 1m20s"), pr.message);
        assertTrue(pr.message.endsWith("(exit code 1)"), pr.message);
        assertTrue(pr.detail.endsWith("ERROR: KC_DB_URL is required"));
        assertTrue(pr.detail.lines().count() <= 20);
        assertTrue(pr.detail.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 2048);
        verify(audit).record(eq("argocd"), eq("promotion.unhealthy"), anyString(), contains("CrashLoopBackOff"));
    }

    @Test
    void createContainerConfigErrorFailsWithoutLogs() {
        when(argo.getApp(APP, false)).thenReturn(app("Synced", "Progressing"));
        when(argo.resourceTree(APP)).thenReturn(Map.of("nodes", List.of(Map.of("kind", "Pod", "name", "upm-x", "namespace", "staging",
                "images", List.of(REPO + ":" + TAG), "health", Map.of("status", "Progressing")))));
        when(argo.resource(eq(APP), any())).thenReturn(Map.of("status", Map.of("containerStatuses", List.of(Map.of(
                "name", "app", "ready", false, "restartCount", 0,
                "state", Map.of("waiting", Map.of("reason", "CreateContainerConfigError",
                        "message", "secret \"profile-manager-runtime\" not found")))))));
        watcher.watch(pr, start.plusSeconds(61));
        assertEquals(Promotion.FAILED, pr.status);
        assertEquals("CreateContainerConfigError: secret \"profile-manager-runtime\" not found", pr.message);
        assertNull(pr.detail);
        verify(argo, never()).logs(any(), any(), any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void podReasonsWaitForTheGracePeriod() {
        when(argo.getApp(APP, false)).thenReturn(app("Synced", "Progressing"));
        watcher.watch(pr, start.plusSeconds(30));
        assertEquals(Promotion.DEPLOYING, pr.status);
        verify(argo, never()).resourceTree(any());
    }

    @Test
    void anOldPodCrashLoopingDoesNotFailTheNewPromotion() {
        when(argo.getApp(APP, false)).thenReturn(app("Synced", "Progressing", REPO + ":oldtag"));
        when(argo.resourceTree(APP)).thenReturn(Map.of("nodes", List.of(Map.of("kind", "Pod", "name", "old", "namespace", "staging",
                "images", List.of(REPO + ":oldtag"), "health", Map.of("status", "Degraded")))));
        when(argo.resource(eq(APP), any())).thenReturn(Map.of("status", Map.of("containerStatuses", List.of(Map.of(
                "name", "app", "ready", false, "restartCount", 9,
                "state", Map.of("waiting", Map.of("reason", "CrashLoopBackOff")))))));
        watcher.watch(pr, start.plusSeconds(90));
        assertEquals(Promotion.DEPLOYING, pr.status);
    }

    @Test
    void degradedAppWithTheNewImageFails() {
        when(argo.getApp(APP, false)).thenReturn(Map.of("status", Map.of("sync", Map.of("status", "Synced"),
                "health", Map.of("status", "Degraded", "message", "Deployment has exceeded its progress deadline"),
                "summary", Map.of("images", List.of(REPO + ":" + TAG)))));
        when(argo.resourceTree(APP)).thenReturn(Map.of("nodes", List.of()));
        watcher.watch(pr, start.plusSeconds(120));
        assertEquals(Promotion.FAILED, pr.status);
        assertEquals("staging is Degraded: Deployment has exceeded its progress deadline", pr.message);
    }

    @Test
    void notHealthyWithinTheTimeoutFails() {
        when(argo.getApp(APP, false)).thenReturn(app("OutOfSync", "Progressing", REPO + ":oldtag"));
        when(argo.resourceTree(APP)).thenReturn(Map.of("nodes", List.of()));
        watcher.watch(pr, start.plus(Duration.ofMinutes(9)));
        assertEquals(Promotion.DEPLOYING, pr.status);
        watcher.watch(pr, start.plus(Duration.ofMinutes(10)));
        assertEquals(Promotion.FAILED, pr.status);
        assertEquals("not healthy after 10 minutes (health Progressing, sync OutOfSync, " + TAG + " not running yet)", pr.message);
    }

    @Test
    void timeoutIsConfigurable() {
        watcher.timeoutMs = Duration.ofMinutes(2).toMillis();
        when(argo.getApp(APP, false)).thenReturn(app("Synced", "Progressing"));
        when(argo.resourceTree(APP)).thenReturn(null);
        watcher.watch(pr, start.plus(Duration.ofMinutes(2)));
        assertEquals(Promotion.FAILED, pr.status);
        assertTrue(pr.message.startsWith("not healthy after 2 minutes"));
    }

    @Test
    void scaledToZeroIsDoneOnceSynced() {
        when(gitops.readValuesCached(any(), eq("staging"))).thenReturn(new GitOpsService.EnvValues(TAG, 0, null));
        when(argo.getApp(APP, false)).thenReturn(app("Synced", "Healthy"));
        watcher.watch(pr, start.plusSeconds(30));
        assertEquals(Promotion.DONE, pr.status);
        assertTrue(pr.message.contains("scaled to 0"));
    }

    @Test
    void aNewerTagInGitSupersedesThePromotion() {
        when(gitops.readValuesCached(any(), eq("staging"))).thenReturn(new GitOpsService.EnvValues("newer", 1, null));
        watcher.watch(pr, start.plusSeconds(30));
        assertEquals(Promotion.FAILED, pr.status);
        assertTrue(pr.message.startsWith("superseded: staging now deploys newer"));
        verifyNoInteractions(argo);
    }

    @Test
    void digestImagesMatchByDigest() {
        String digest = "sha256:" + "a".repeat(64);
        assertTrue(PromotionWatcher.runsImage(Map.of("summary", Map.of("images", List.of(REPO + "@" + digest))), REPO, digest));
        assertFalse(PromotionWatcher.runsImage(Map.of("summary", Map.of("images", List.of(REPO + ":" + TAG + "x"))), REPO, TAG));
    }

    @Test
    void tailKeepsTheLastLinesWithinTheByteBudget() {
        assertNull(PromotionWatcher.tail("  "));
        assertEquals("a\nb", PromotionWatcher.tail("a\nb\n"));
        String oneHugeLine = "é".repeat(3000);
        String t = PromotionWatcher.tail(oneHugeLine);
        assertTrue(t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 2048);
        assertFalse(t.contains("�"));
    }
}
