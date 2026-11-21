package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.domain.Project;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Values on main still carry the chart default ingress class (nginx) and hosts on the old GCP IP 34.180.3.18.nip.io;
 * the platform owns className / controller / the generated host per cluster and re-applies them.
 */
class ClusterIngressSettingsTest {
    private static final String STALE = """
            # Managed by AIO platform - image.tag is updated by CI / promotions
            image:
              repository: ghcr.io/o/shop
              tag: "abc123"
            replicaCount: 1
            ingress:
              enabled: true
              host: shop-staging.34.180.3.18.nip.io
              className: nginx
            routes:
              - host: shop-staging.34.180.3.18.nip.io
                path: /api
            """;

    private final GitHubClient github = mock(GitHubClient.class);
    private final GitOpsService gitops;
    private final Project shop = EnvironmentsTest.project(1, "shop");

    ClusterIngressSettingsTest() {
        EnvironmentRegistry envs = EnvironmentRegistry.fromConfig(EnvironmentsTest.liveProps(), "aks");
        var clusters = new ClusterRegistry(envs, mock(KubernetesClient.class));
        gitops = new GitOpsService(github, EnvironmentsTest.liveProps(), mock(RolloutService.class), clusters, mock(SpotDefaults.class));
        gitops.environments = envs;
    }

    @Test
    void writesClassControllerAndMovesTheGeneratedNipIoHostOnly() {
        String out = gitops.clusterSettings(STALE, shop, "staging");
        assertTrue(out.contains("  host: shop-staging.52.140.55.184.nip.io\n"), out);
        assertTrue(out.contains("  className: aio-azure\n"), out);
        assertTrue(out.contains("  controller: traefik\n"), out);
        // routes are never touched, even when they look generated
        assertTrue(out.contains("  - host: shop-staging.34.180.3.18.nip.io\n    path: /api\n"), out);
        // idempotent
        assertEquals(out, gitops.clusterSettings(out, shop, "staging"));
    }

    @Test
    void customHostsAndOtherAppsHostsStay() {
        String custom = STALE.replace("host: shop-staging.34.180.3.18.nip.io\n  className", "host: shop.example.com\n  className");
        String out = gitops.clusterSettings(custom, shop, "staging");
        assertTrue(out.contains("  host: shop.example.com\n"), out);
        // another env's name is not this env's generated host
        String other = STALE.replace("host: shop-staging.34.180.3.18.nip.io\n  className", "host: shop-dev.34.180.3.18.nip.io\n  className");
        assertTrue(gitops.clusterSettings(other, shop, "staging").contains("  host: shop-dev.34.180.3.18.nip.io\n"));
        // prod's generated host has no env suffix
        String prod = STALE.replace("shop-staging.34.180.3.18.nip.io\n  className", "shop.34.180.3.18.nip.io\n  className");
        assertTrue(gitops.clusterSettings(prod, shop, "prod").contains("  host: shop.52.140.55.184.nip.io\n"));
    }

    @Test
    void dryRunReturnsTheDiffWithoutCommitting() {
        when(github.getFile("o", "aio-gitops", "apps/shop/values-staging.yaml", "main")).thenReturn(new GitHubClient.FileContent(STALE, "s1"));
        var preview = gitops.reapplyClusterSettings(shop, "staging", true, "msg");
        assertTrue(preview.changed());
        assertTrue(preview.diff().contains("- " + "  host: shop-staging.34.180.3.18.nip.io"), preview.diff().toString());
        assertTrue(preview.diff().contains("+ " + "  host: shop-staging.52.140.55.184.nip.io"), preview.diff().toString());
        assertTrue(preview.diff().contains("+ " + "  controller: traefik"), preview.diff().toString());
        verify(github, never()).putFile(any(), any(), any(), any(), any(), any(), any());

        var applied = gitops.reapplyClusterSettings(shop, "staging", false, "msg");
        assertTrue(applied.changed());
        verify(github).putFile(eq("o"), eq("aio-gitops"), eq("apps/shop/values-staging.yaml"), eq("main"),
                argThat(s -> s.contains("className: aio-azure")), eq("s1"), eq("msg"));
    }

    @Test
    void lineDiffIsMinimal() {
        assertEquals(List.of("- b", "+ c"), GitOpsService.lineDiff("a\nb\nd", "a\nc\nd"));
        assertEquals(List.of(), GitOpsService.lineDiff("a\nb", "a\nb"));
    }
}
