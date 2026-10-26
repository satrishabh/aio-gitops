package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aio.platform.config.AppProperties.Cluster;
import com.aio.platform.domain.Project;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiClusterTest {
    private static final Cluster NONPROD = new Cluster("aio-nonprod", "https://10.0.0.1", List.of("dev", "staging"), "1.2.3.4.nip.io", false);
    private static final Cluster PROD = new Cluster("aio-prod", "https://10.0.0.2", List.of("prod"), "5.6.7.8.nip.io", true);

    @Test
    void mapsEveryEnvToOneCluster() {
        var m = ClusterRegistry.index(List.of(NONPROD, PROD));
        assertEquals("aio-nonprod", m.get("dev").name());
        assertEquals("aio-nonprod", m.get("staging").name());
        assertEquals("aio-prod", m.get("prod").name());
    }

    @Test
    void rejectsUnmappedDuplicateOrUnknownEnvs() {
        assertThrows(IllegalStateException.class, () -> ClusterRegistry.index(List.of(NONPROD)));   // prod unmapped
        var dupe = new Cluster("other", "https://x", List.of("prod", "dev"), null, null);
        assertThrows(IllegalStateException.class, () -> ClusterRegistry.index(List.of(NONPROD, PROD, dupe)));
        var unknown = new Cluster("qa", "https://x", List.of("qa"), null, null);
        assertThrows(IllegalStateException.class, () -> ClusterRegistry.index(List.of(NONPROD, PROD, unknown)));
        var badName = new Cluster("Bad_Name", "https://x", List.of(), null, null);
        assertThrows(IllegalStateException.class, () -> ClusterRegistry.index(List.of(NONPROD, PROD, badName)));
    }

    @Test
    void detectsWhichClusterAnAppIsOn() {
        Map<String, Object> legacy = Map.of("spec", Map.of("destination", Map.of("server", "https://kubernetes.default.svc", "namespace", "dev")));
        Map<String, Object> byName = Map.of("spec", Map.of("destination", Map.of("name", "aio-nonprod", "namespace", "dev")));
        Map<String, Object> byServer = Map.of("spec", Map.of("destination", Map.of("server", "https://10.0.0.1", "namespace", "dev")));
        assertFalse(ArgoCdService.onCluster(legacy, NONPROD));   // hub app -> must move
        assertTrue(ArgoCdService.onCluster(byName, NONPROD));
        assertTrue(ArgoCdService.onCluster(byServer, NONPROD));
        assertFalse(ArgoCdService.onCluster(byName, PROD));
        var hub = new Cluster(ClusterRegistry.IN_CLUSTER, ClusterRegistry.IN_CLUSTER_SERVER, List.of("dev"), null, null);
        assertTrue(ArgoCdService.onCluster(legacy, hub));         // single-cluster fallback: nothing moves
    }

    @Test
    void parsesArgoLogStream() {
        String body = """
                {"result":{"content":"Started app in 3.2s","timeStamp":"2026-10-26T10:00:00Z"}}
                {"result":{"content":"connection refused","timeStamp":"2026-10-26T10:00:01Z"}}
                {"result":{"content":"","last":true}}
                """;
        assertEquals("Started app in 3.2s\nconnection refused\n", ArgoCdService.parseLogStream(body));
        assertEquals("", ArgoCdService.parseLogStream(""));
        assertTrue(ArgoCdService.parseLogStream("{\"error\":{\"message\":\"container not found\"}}").contains("container not found"));
    }

    @Test
    void movesOnlyGeneratedHostsToTheNewDomain() {
        Project p = new Project();
        p.name = "shop";
        String yaml = """
                ingress:
                  enabled: true
                  host: shop-dev.34.180.3.18.nip.io
                """;
        String from = "34.180.3.18.nip.io", to = "1.2.3.4.nip.io";
        String moved = GitOpsService.replaceHost(yaml, "host", GitOpsService.hostOn(p, "dev", from), GitOpsService.hostOn(p, "dev", to));
        assertTrue(moved.contains("  host: shop-dev.1.2.3.4.nip.io\n"), moved);
        // a custom host is not the generated one, so it is left alone
        String custom = yaml.replace("shop-dev.34.180.3.18.nip.io", "shop.example.com");
        assertEquals(custom, GitOpsService.replaceHost(custom, "host", GitOpsService.hostOn(p, "dev", from), GitOpsService.hostOn(p, "dev", to)));
        assertEquals("shop.1.2.3.4.nip.io", GitOpsService.hostOn(p, "prod", to));
    }
}
