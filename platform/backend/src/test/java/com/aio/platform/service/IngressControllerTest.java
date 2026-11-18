package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Per-cluster ingress class / controller: config validation and how they are written into values files. */
class IngressControllerTest {

    private static final String VALUES = """
            image:
              repository: ghcr.io/acme/shop
              tag: "abc"
            ingress:
              enabled: true
              host: shop-dev.1.2.3.4.nip.io
            routes: []
            """;

    @Test
    void writesClassAndControllerIntoIngressBlock() {
        String out = GitOpsService.applyIngressClass(VALUES, "aio-azure", "traefik");
        assertTrue(out.contains("ingress:\n  enabled: true\n  host: shop-dev.1.2.3.4.nip.io\n  className: aio-azure\n  controller: traefik\n"), out);
        assertTrue(out.endsWith("routes: []\n"), "blocks after ingress are untouched");
    }

    @Test
    void replacesExistingValuesAndIsIdempotent() {
        String nginx = VALUES.replace("  host:", "  className: nginx\n  controller: nginx\n  host:");
        String once = GitOpsService.applyIngressClass(nginx, "aio-azure", "traefik");
        assertTrue(once.contains("  className: aio-azure\n  controller: traefik\n"), once);
        assertFalse(once.contains("nginx"), once);
        assertEquals(once, GitOpsService.applyIngressClass(once, "aio-azure", "traefik"));
    }

    @Test
    void unconfiguredClusterLeavesValuesUntouched() {
        assertEquals(VALUES, GitOpsService.applyIngressClass(VALUES, null, null));
        assertEquals(VALUES, GitOpsService.applyIngressClass(VALUES, " ", ""));
    }

    @Test
    void valuesWithoutIngressBlockAreUntouched() {
        String noIngress = "image:\n  repository: x\nroutes: []\n";
        assertEquals(noIngress, GitOpsService.applyIngressClass(noIngress, "aio-azure", "traefik"));
    }

    @Test
    void controllerIsValidatedAndNormalised() {
        assertEquals("traefik", ClusterRegistry.controller("azure", " Traefik "));
        assertEquals("nginx", ClusterRegistry.controller("gke", "nginx"));
        assertNull(ClusterRegistry.controller("gke", null));
        assertNull(ClusterRegistry.controller("gke", " "));
        var e = assertThrows(IllegalStateException.class, () -> ClusterRegistry.controller("azure", "haproxy"));
        assertTrue(e.getMessage().contains("azure"), e.getMessage());
    }
}
