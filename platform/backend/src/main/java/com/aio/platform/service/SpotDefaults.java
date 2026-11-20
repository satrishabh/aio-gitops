package com.aio.platform.service;

import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether a new app's values for an environment opt in to Spot nodes ({@code spot:} in the app chart).
 * Only when all hold:
 * <ul>
 *   <li>the environment is dev or staging (the chart refuses Spot in prod anyway);</li>
 *   <li>the environment runs on this platform's own cluster (connectors manage only that cluster);</li>
 *   <li>{@code CLOUD_PROVIDER} is aks or gke, the clouds the node-autoprovisioning connector supports;</li>
 *   <li>that connector is installed. On GKE this matters: the compute-class selector without the ComputeClass
 *       would leave the pod Pending. On AKS the toleration alone would be harmless.</li>
 * </ul>
 * Fails soft: if Argo CD cannot be asked, the app is created without Spot.
 */
@Component
public class SpotDefaults {
    private static final Logger log = LoggerFactory.getLogger(SpotDefaults.class);
    static final String CONNECTOR = "node-autoprovisioning";
    static final Set<String> ENVS = Set.of("dev", "staging");
    static final Set<String> PROVIDERS = Set.of("aks", "gke");

    private final ConnectorCatalog catalog;
    private final ArgoCdService argo;
    private final ClusterRegistry clusters;
    private final boolean enabled;

    public SpotDefaults(ConnectorCatalog catalog, ArgoCdService argo, ClusterRegistry clusters,
                        @Value("${app.connectors.spot-for-new-apps:auto}") String mode) {
        this.catalog = catalog;
        this.argo = argo;
        this.clusters = clusters;
        String m = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if (!m.equals("auto") && !m.equals("off"))
            throw new IllegalStateException("app.connectors.spot-for-new-apps must be auto or off");
        this.enabled = m.equals("auto");
    }

    /** The app chart's spot.provider for a new app in this environment, or null for no Spot. */
    public String providerFor(String env) {
        if (!enabled || !ENVS.contains(env) || !PROVIDERS.contains(catalog.cloud())) return null;
        try {
            if (!clusters.inCluster(clusters.clusterFor(env))) return null;
            if (catalog.find(CONNECTOR).isEmpty()) return null;
            return argo.getApp("connector-" + CONNECTOR, false) != null ? catalog.cloud() : null;
        } catch (RuntimeException e) {
            log.warn("spot default for {} not decided, creating without Spot: {}", env, e.getMessage());
            return null;
        }
    }

    /** The values block that opts in. */
    static String block(String provider) {
        return "spot:\n  enabled: true\n  provider: " + provider + "\n";
    }
}
