package com.aio.platform.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientsConfigTest {

    @Test
    void allowsInsecureOnlyForInClusterOrLocalArgoCd() {
        assertDoesNotThrow(() -> ClientsConfig.requireInClusterOrLocal("https://argocd-server.argocd.svc"));
        assertDoesNotThrow(() -> ClientsConfig.requireInClusterOrLocal("https://argocd.argocd.svc.cluster.local:8080"));
        assertDoesNotThrow(() -> ClientsConfig.requireInClusterOrLocal("https://localhost:8443"));
        assertDoesNotThrow(() -> ClientsConfig.requireInClusterOrLocal("https://127.0.0.1:8443"));
    }

    @Test
    void refusesInsecureForPublicHosts() {
        assertThrows(IllegalStateException.class,
                () -> ClientsConfig.requireInClusterOrLocal("https://argocd.example.com"));
        assertThrows(IllegalStateException.class,
                () -> ClientsConfig.requireInClusterOrLocal("https://argocd.svc.example.com"));
    }
}
