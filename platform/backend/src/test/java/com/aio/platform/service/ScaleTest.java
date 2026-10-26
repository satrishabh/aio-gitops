package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScaleTest {
    private static final String VALUES = """
            image:
              repository: ghcr.io/satrishabh/app
              tag: "abc123"
            replicaCount: 2
            imagePullSecrets:
              - name: ghcr-pull
            """;

    @Test
    void scaleDownMarksEnvAndDeploysKeepItStopped() {
        String down = GitOpsService.scale(VALUES, 0);
        assertEquals(0, GitOpsService.extractReplicas(down));
        assertTrue(GitOpsService.isScaledDown(down));
        // a later CI deploy / promotion updates the image but must not wake the app up
        String deployed = GitOpsService.ensureRunning(GitOpsService.normalize(GitOpsService.replaceTag(down, "def456")));
        assertEquals(0, GitOpsService.extractReplicas(deployed));
        assertEquals("def456", GitOpsService.extractTag(deployed));
    }

    @Test
    void scaleUpClearsMarkerAndIsIdempotent() {
        String up = GitOpsService.scale(GitOpsService.scale(VALUES, 0), 3);
        assertEquals(3, GitOpsService.extractReplicas(up));
        assertFalse(GitOpsService.isScaledDown(up));
        assertFalse(up.contains("scaledDown"));
        assertEquals(GitOpsService.scale(VALUES, 0), GitOpsService.scale(GitOpsService.scale(VALUES, 0), 0));
    }

    @Test
    void validation() {
        assertDoesNotThrow(() -> ScaleService.validate("dev", 0, false));
        assertDoesNotThrow(() -> ScaleService.validate("prod", 1, true));
        assertThrows(SecurityException.class, () -> ScaleService.validate("prod", 0, false));
        assertThrows(IllegalArgumentException.class, () -> ScaleService.validate("qa", 1, true));
        assertThrows(IllegalArgumentException.class, () -> ScaleService.validate("dev", 6, true));
        assertThrows(IllegalArgumentException.class, () -> ScaleService.validate("dev", -1, true));
    }
}
