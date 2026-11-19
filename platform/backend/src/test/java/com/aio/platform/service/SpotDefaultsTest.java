package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpotDefaultsTest {
    private static final String VALUES = """
            image:
              repository: ghcr.io/satrishabh/app
              tag: "abc123"
            """;

    private final ConnectorCatalog catalog = mock(ConnectorCatalog.class);
    private final ArgoCdService argo = mock(ArgoCdService.class);
    private final ClusterRegistry clusters = mock(ClusterRegistry.class);

    private SpotDefaults spot(String cloud, boolean installed, boolean inCluster, String mode) {
        when(catalog.cloud()).thenReturn(cloud);
        when(catalog.find(SpotDefaults.CONNECTOR)).thenReturn(Optional.of(mock(ConnectorCatalog.Connector.class)));
        when(argo.getApp("connector-node-autoprovisioning", false)).thenReturn(installed ? Map.of() : null);
        when(clusters.inCluster(any())).thenReturn(inCluster);
        return new SpotDefaults(catalog, argo, clusters, mode);
    }

    @Test
    void devAndStagingOptInWhenTheConnectorIsInstalled() {
        SpotDefaults s = spot("gke", true, true, "auto");
        assertEquals("gke", s.providerFor("dev"));
        assertEquals("gke", s.providerFor("staging"));
        assertNull(s.providerFor("prod"));
        assertEquals("aks", spot("aks", true, true, "auto").providerFor("dev"));
    }

    @Test
    void noSpotWithoutTheConnectorOnOtherCloudsOnRemoteClustersOrWhenOff() {
        assertNull(spot("gke", false, true, "auto").providerFor("dev"));
        assertNull(spot("eks", true, true, "auto").providerFor("dev"));
        assertNull(spot("any", true, true, "auto").providerFor("dev"));
        assertNull(spot("aks", true, false, "auto").providerFor("dev"));
        assertNull(spot("aks", true, true, "off").providerFor("dev"));
    }

    @Test
    void argoErrorsMeanNoSpot() {
        SpotDefaults s = spot("aks", true, true, "auto");
        when(argo.getApp(any(), anyBoolean())).thenThrow(new IllegalStateException("argo down"));
        assertNull(s.providerFor("dev"));
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalStateException.class, () -> new SpotDefaults(catalog, argo, clusters, "always"));
    }

    @Test
    void cdPageRefusesSpotInProdAndUnknownProviders() {
        String on = VALUES + SpotDefaults.block("aks");
        assertDoesNotThrow(() -> GitOpsService.validateValues(on, null, "dev"));
        var e = assertThrows(IllegalArgumentException.class, () -> GitOpsService.validateValues(on, null, "prod"));
        assertTrue(e.getMessage().contains("not prod"));
        assertDoesNotThrow(() -> GitOpsService.validateValues(VALUES + "spot:\n  enabled: false\n", null, "prod"));
        assertThrows(IllegalArgumentException.class,
                () -> GitOpsService.validateValues(VALUES + "spot:\n  enabled: true\n  provider: eks\n", null, "dev"));
        assertThrows(IllegalArgumentException.class, () -> GitOpsService.validateValues(VALUES + "spot: yes\n", null, "dev"));
    }
}
