package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CdValuesTest {
    private static final String REPO = "ghcr.io/satrishabh/app";
    private static final String VALUES = """
            image:
              repository: ghcr.io/satrishabh/app
              tag: "abc123"
            replicaCount: 2
            ingress:
              enabled: true
              host: app-dev.example.com
            """;

    @Test
    void acceptsValidValues() {
        assertDoesNotThrow(() -> GitOpsService.validateValues(VALUES, REPO));
    }

    @Test
    void rejectsBrokenYaml() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> GitOpsService.validateValues("image:\n  tag: [unclosed\n", REPO));
        assertTrue(e.getMessage().startsWith("invalid YAML"));
    }

    @Test
    void rejectsMissingOrBadTag() {
        assertThrows(IllegalArgumentException.class,
                () -> GitOpsService.validateValues("image:\n  repository: " + REPO + "\nreplicaCount: 1\n", REPO));
        assertThrows(IllegalArgumentException.class,
                () -> GitOpsService.validateValues(VALUES.replace("abc123", "bad tag;"), REPO));
    }

    @Test
    void rejectsOtherImageRepository() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> GitOpsService.validateValues(VALUES.replace(REPO, "docker.io/evil/miner"), REPO));
        assertTrue(e.getMessage().contains(REPO));
    }

    @Test
    void rejectsOutOfRangeReplicas() {
        assertThrows(IllegalArgumentException.class, () -> GitOpsService.validateValues(VALUES.replace("replicaCount: 2", "replicaCount: 50"), REPO));
        assertThrows(IllegalArgumentException.class, () -> GitOpsService.validateValues(VALUES.replace("replicaCount: 2", "replicaCount: two"), REPO));
    }

    @Test
    void rejectsNonMapping() {
        assertThrows(IllegalArgumentException.class, () -> GitOpsService.validateValues("- a\n- b\n", REPO));
    }
}
