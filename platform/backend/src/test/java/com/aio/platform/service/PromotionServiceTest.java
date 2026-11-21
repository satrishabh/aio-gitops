package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.PromotionRepo;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PromotionServiceTest {
    PromotionRepo promotions = mock(PromotionRepo.class);
    GitOpsService gitops = mock(GitOpsService.class);
    ArgoCdService argo = mock(ArgoCdService.class);
    AuditService audit = mock(AuditService.class);
    NamespaceObjects objects = mock(NamespaceObjects.class);
    ClusterRegistry clusters = mock(ClusterRegistry.class);
    PromotionService service = new PromotionService(promotions, gitops, argo, audit, mock(AppProperties.class), objects, clusters);
    Project p = new Project();

    @BeforeEach
    void setUp() {
        p.id = 7L;
        p.name = "user-profile-manager";
        p.imageRepo = "ghcr.io/satrishabh/user-profile-manager";
        when(promotions.save(any())).thenAnswer(i -> i.getArgument(0));
        when(clusters.clusterFor(anyString())).thenReturn(new AppProperties.Cluster("in-cluster", "https://kubernetes.default.svc",
                List.of("dev", "staging", "prod"), null, null));
        when(gitops.readTag(p, "dev")).thenReturn("d5cd7e4d7180");
        when(gitops.readRaw(p, "dev")).thenReturn(new GitOpsService.RawValues("dev", PromotionCheckTest.DEV, "s1"));
        when(gitops.readRaw(p, "staging")).thenReturn(new GitOpsService.RawValues("staging", PromotionCheckTest.STAGING, "s2"));
        when(objects.canCheck(anyString())).thenReturn(true);
        when(objects.exists(eq("staging"), eq("staging"), any())).thenAnswer(i ->
                ((PromotionCheck.ObjectRef) i.getArgument(2)).name().equals("ghcr-pull"));
    }

    @Test
    void checkReportsValuesAndNamespaceFindings() {
        var c = service.check(p, "dev", "staging");
        assertFalse(c.ok());
        assertTrue(c.clusterChecked());
        assertEquals("staging", c.namespace());
        assertTrue(c.missing().stream().anyMatch(f -> f.kind().equals("secret") && f.name().equals("profile-manager-runtime")));
        assertTrue(c.missing().stream().anyMatch(f -> f.kind().equals("configMap") && f.name().equals("rehearsal-db-trust")));
        assertTrue(c.neverCopied().containsAll(List.of("ingress", "routes", "replicaCount", "resources", "spot", "services", "image")));
    }

    @Test
    void workloadClustersAreNotCheckedButSaySo() {
        when(objects.canCheck("staging")).thenReturn(false);
        var c = service.check(p, "dev", "staging");
        assertFalse(c.clusterChecked());
        assertNotNull(c.clusterNote());
        assertTrue(c.missing().stream().allMatch(PromotionCheck.Finding::copyable));
        verify(objects, never()).exists(any(), any(), any());
    }

    @Test
    void findingsBlockAPlainPromotion() {
        var e = assertThrows(IllegalArgumentException.class, () -> service.promote(p, "dev", "staging", "alice"));
        assertTrue(e.getMessage().contains("type the project name"));
        verify(gitops, never()).setTag(any(), any(), any(), any());
        verify(promotions, never()).save(any());
    }

    @Test
    void copySettingsStillNeedsConfirmationForMissingSecrets() {
        var e = assertThrows(IllegalArgumentException.class, () -> service.promote(p, "dev", "staging", "alice", true, null));
        assertTrue(e.getMessage().contains("found 2 problems"), e.getMessage());
        assertTrue(e.getMessage().contains("Copy settings can't fix"));
    }

    @Test
    void promoteAnywayIsAuditedAndDeploying() {
        Promotion pr = service.promote(p, "dev", "staging", "alice", false, "user-profile-manager");
        assertEquals(Promotion.DEPLOYING, pr.status);
        assertNotNull(pr.deployingSince);
        verify(gitops).setTag(eq(p), eq("staging"), eq("d5cd7e4d7180"), anyString());
        verify(audit).record(eq("alice"), eq("promotion.force"), eq("user-profile-manager/staging"), contains("secret profile-manager-runtime"));
        verify(argo).getApp("user-profile-manager-staging", true);
    }

    @SuppressWarnings("unchecked")
    @Test
    void copySettingsWritesSettingsAndTagInOneCommit() {
        when(objects.exists(any(), any(), any())).thenReturn(true);   // the Secret and ConfigMap were created
        Promotion pr = service.promote(p, "dev", "staging", "alice", true, null);
        assertEquals(Promotion.DEPLOYING, pr.status);
        assertTrue(pr.copySettings);
        ArgumentCaptor<UnaryOperator<String>> settings = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(gitops).setTagWithSettings(eq(p), eq("staging"), eq("d5cd7e4d7180"), settings.capture(), contains("with settings from dev"));
        verify(gitops, never()).setTag(any(), any(), any(), any());
        assertEquals(PromotionCheck.merge(PromotionCheckTest.DEV, PromotionCheckTest.STAGING), settings.getValue().apply(PromotionCheckTest.STAGING));
        verify(audit, never()).record(any(), eq("promotion.force"), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void editedEnvValuesAreStoredAndCommittedWithTheTag() {
        when(objects.exists(any(), any(), any())).thenReturn(true);
        Promotion pr = service.promote(p, "dev", "staging", "alice", true, null,
                java.util.Map.of("KC_HOSTNAME", "https://profile-staging.example.com"));
        assertEquals("{\"KC_HOSTNAME\":\"https://profile-staging.example.com\"}", pr.envOverrides);
        ArgumentCaptor<UnaryOperator<String>> settings = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(gitops).setTagWithSettings(eq(p), eq("staging"), eq("d5cd7e4d7180"), settings.capture(), anyString());
        String written = settings.getValue().apply(PromotionCheckTest.STAGING);
        assertTrue(written.contains("value: \"https://profile-staging.example.com\""));
        assertFalse(written.contains("profile.sarvaikam.com"));
    }

    @Test
    void envOverridesNeedCopySettingsAndValidNames() {
        when(objects.exists(any(), any(), any())).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.promote(p, "dev", "staging", "alice", false,
                "user-profile-manager", java.util.Map.of("KC_HOSTNAME", "x")));
        assertThrows(IllegalArgumentException.class, () -> service.promote(p, "dev", "staging", "alice", true, null,
                java.util.Map.of("KC_DB", "x")));
        verify(promotions, never()).save(any());
    }

    @Test
    void cleanCheckPromotesWithoutConfirmation() {
        when(gitops.readRaw(p, "staging")).thenReturn(new GitOpsService.RawValues("staging", PromotionCheckTest.DEV, "s2"));
        when(objects.exists(any(), any(), any())).thenReturn(true);
        assertEquals(Promotion.DEPLOYING, service.promote(p, "dev", "staging", "alice").status);
    }

    @Test
    void prodWaitsForApprovalThenCopiesOnApply() {
        when(gitops.readTag(p, "staging")).thenReturn("d5cd7e4d7180");
        when(gitops.readRaw(p, "staging")).thenReturn(new GitOpsService.RawValues("staging", PromotionCheckTest.DEV, "s2"));
        when(gitops.readRaw(p, "prod")).thenReturn(new GitOpsService.RawValues("prod", PromotionCheckTest.STAGING, "s3"));
        when(objects.exists(any(), any(), any())).thenReturn(true);
        Promotion pr = service.promote(p, "staging", "prod", "alice", true, null);
        assertEquals(Promotion.PENDING, pr.status);
        verifyNoInteractions(argo);
        verify(gitops, never()).setTagWithSettings(any(), any(), any(), any(), any());
    }

    @Test
    void commitFailureIsFailedNotDeploying() {
        when(objects.exists(any(), any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("values file missing")).when(gitops).setTagWithSettings(any(), any(), any(), any(), any());
        Promotion pr = service.promote(p, "dev", "staging", "alice", true, null);
        assertEquals(Promotion.FAILED, pr.status);
        assertEquals("values file missing", pr.message);
    }
}
