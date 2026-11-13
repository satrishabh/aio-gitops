package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.CiRunRepo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class BuildReleaseWorkerTest {
    Project project() {
        Project p = new Project(); p.id = 1L; p.name = "demo";
        p.imageRepo = "ghcr.io/team/demo"; p.ownerSubject = "issuer|owner"; return p;
    }
    CiRun run() {
        CiRun r = new CiRun(); r.id = 2L; r.projectId = 1L; r.deploy = true;
        r.deliveryMode = "INDEPENDENT"; r.status = CiRun.SUCCEEDED; r.releaseStatus = "READY";
        r.releaseOwnerSubject = "issuer|owner"; r.releaseImageRepo = "ghcr.io/team/demo";
        r.commitSha = "a".repeat(40); r.imageDigest = "sha256:" + "b".repeat(64);
        r.imageUrl = r.releaseImageRepo + ":" + "a".repeat(12); r.scanSeverity = "HIGH,CRITICAL";
        r.vulnCritical = 0; r.vulnHigh = 0; return r;
    }
    @Test void argoIsNotRequiredAndDuplicateDispatchDoesNotWriteAgain() {
        Project p = project(); CiRun r = run();
        var entities = mock(EntityManager.class); var runs = mock(CiRunRepo.class); var gitops = mock(GitOpsService.class);
        when(entities.find(CiRun.class, 2L)).thenReturn(r);
        when(entities.find(Project.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(p);
        when(runs.findFirstByProjectIdAndDeliveryModeAndDeployTrueOrderByIdDesc(1L, "INDEPENDENT")).thenReturn(Optional.of(r));
        var worker = new BuildReleaseWorker(runs, entities, mock(TransactionTemplate.class), gitops);
        worker.apply(2L); worker.apply(2L);
        assertEquals("COMMITTED", r.releaseStatus); assertEquals(CiRun.SUCCEEDED, r.status);
        verify(gitops, times(1)).setTag(eq(p), eq("dev"), eq(r.imageDigest), anyString());
    }
    @Test void failedWriteRetainsSuccessfulBuildAndDurableRetry() {
        Project p = project(); CiRun r = run();
        var entities = mock(EntityManager.class); var runs = mock(CiRunRepo.class); var gitops = mock(GitOpsService.class);
        when(entities.find(CiRun.class, 2L)).thenReturn(r);
        when(entities.find(Project.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(p);
        when(runs.findFirstByProjectIdAndDeliveryModeAndDeployTrueOrderByIdDesc(1L, "INDEPENDENT")).thenReturn(Optional.of(r));
        doThrow(new IllegalStateException("outage")).when(gitops).setTag(any(), anyString(), anyString(), anyString());
        new BuildReleaseWorker(runs, entities, mock(TransactionTemplate.class), gitops).apply(2L);
        assertEquals(CiRun.SUCCEEDED, r.status); assertEquals("RETRY_WAIT", r.releaseStatus);
        assertNotNull(r.releaseNextAttempt); assertFalse(BuildReleaseWorker.eligible(r, Instant.now()));
    }
    @Test void oldBuildCannotOverwriteNewIntent() {
        Project p = project(); CiRun r = run(); CiRun newer = run(); newer.id = 3L;
        var entities = mock(EntityManager.class); var runs = mock(CiRunRepo.class); var gitops = mock(GitOpsService.class);
        when(entities.find(CiRun.class, 2L)).thenReturn(r);
        when(entities.find(Project.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(p);
        when(runs.findFirstByProjectIdAndDeliveryModeAndDeployTrueOrderByIdDesc(1L, "INDEPENDENT")).thenReturn(Optional.of(newer));
        new BuildReleaseWorker(runs, entities, mock(TransactionTemplate.class), gitops).apply(2L);
        assertEquals("SUPERSEDED", r.releaseStatus); verifyNoInteractions(gitops);
    }
    @Test void rejectsMissingEvidenceRiskOverridesAndOwnershipChanges() {
        var p = project(); var r = run();
        assertNull(BuildReleaseWorker.invalidResult(r, p));
        r.gateOverridden = true; assertNotNull(BuildReleaseWorker.invalidResult(r, p));
        r = run(); r.imageDigest = "latest"; assertNotNull(BuildReleaseWorker.invalidResult(r, p));
        r = run(); r.vulnCritical = 1; assertNotNull(BuildReleaseWorker.invalidResult(r, p));
        r = run(); p.ownerSubject = "other"; assertNotNull(BuildReleaseWorker.invalidResult(r, p));
    }
    @Test void digestValuesArePreservedAndMalformedDigestsRejected() {
        String digest = "sha256:" + "a".repeat(64);
        String yaml = "image:\n  repository: ghcr.io/team/demo\n  tag: old\n";
        assertEquals(digest, GitOpsService.extractTag(GitOpsService.replaceTag(yaml, digest)));
        assertThrows(IllegalArgumentException.class, () -> GitOpsService.replaceTag(yaml, "sha256:bad"));
    }
}
