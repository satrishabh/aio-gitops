package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.config.AppProperties.Cluster;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Which cluster each environment deploys to. The hub (where the platform, Tekton and Argo CD run) only
 * reaches workload clusters through Argo CD; this registry just names them.
 *
 * <p>With no {@code app.clusters} configured everything targets Argo CD's {@code in-cluster} (the
 * original single-cluster layout), so existing installs keep working until clusters are added.
 */
@Service
public class ClusterRegistry {
    private static final Logger log = LoggerFactory.getLogger(ClusterRegistry.class);
    public static final String IN_CLUSTER = "in-cluster";
    public static final String IN_CLUSTER_SERVER = "https://kubernetes.default.svc";
    private static final Duration CRD_CACHE = Duration.ofMinutes(5);

    private final Map<String, Cluster> byEnv;
    private final List<Cluster> clusters;
    private final KubernetesClient hub;
    private volatile Boolean hubRollouts;
    private volatile Instant hubCheckedAt = Instant.EPOCH;

    public ClusterRegistry(AppProperties props, KubernetesClient hub) {
        this.hub = hub;
        this.clusters = resolve(props);
        this.byEnv = index(clusters);
        clusters.forEach(c -> log.info("cluster {} ({}) hosts {}", c.name(), c.server(), c.envs()));
    }

    /** Configured clusters, or the single in-cluster fallback built from the legacy settings. */
    static List<Cluster> resolve(AppProperties props) {
        List<Cluster> configured = props.clusters() == null ? List.of() : props.clusters().stream()
                .filter(c -> c != null && c.name() != null && !c.name().isBlank())
                .map(c -> new Cluster(c.name().trim(), c.server(), c.envs() == null ? List.of() : c.envs().stream().map(String::trim).toList(),
                        c.baseDomain(), c.rollouts(), blankToNull(c.ingressIp()), blankToNull(c.issuer()), zone(c.dnsZone()), c.tls()))
                .toList();
        if (configured.isEmpty()) {
            String server = props.argocd() == null || props.argocd().destinationServer() == null
                    ? IN_CLUSTER_SERVER : props.argocd().destinationServer();
            return List.of(new Cluster(IN_CLUSTER, server, GitOpsService.ENVS, props.baseDomain(), null));
        }
        return configured;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** "Example.COM." -> "example.com" */
    static String zone(String z) {
        String s = blankToNull(z);
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT).replaceAll("^\\.|\\.$", "");
    }

    /** env -> cluster; fails fast when an env is unmapped or mapped twice (a typo must not deploy prod elsewhere). */
    static Map<String, Cluster> index(List<Cluster> clusters) {
        Map<String, Cluster> m = new LinkedHashMap<>();
        for (Cluster c : clusters) {
            if (c.name() == null || !c.name().matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?"))
                throw new IllegalStateException("invalid cluster name: " + c.name());
            for (String env : c.envs() == null ? List.<String>of() : c.envs()) {
                if (!GitOpsService.ENVS.contains(env)) throw new IllegalStateException("cluster " + c.name() + ": unknown env " + env);
                Cluster prev = m.put(env, c);
                if (prev != null) throw new IllegalStateException("env " + env + " is mapped to both " + prev.name() + " and " + c.name());
            }
        }
        for (String env : GitOpsService.ENVS)
            if (!m.containsKey(env)) throw new IllegalStateException("env " + env + " is not mapped to any cluster (app.clusters)");
        return m;
    }

    public Cluster clusterFor(String env) {
        Cluster c = byEnv.get(env);
        if (c == null) throw new IllegalArgumentException("unknown environment: " + env);
        return c;
    }

    public List<Cluster> clusters() {
        return clusters;
    }

    public boolean inCluster(Cluster c) {
        return IN_CLUSTER.equals(c.name()) || IN_CLUSTER_SERVER.equals(c.server());
    }

    public String baseDomainFor(String env) {
        String d = clusterFor(env).baseDomain();
        return d == null || d.isBlank() ? null : d.trim();
    }

    // ------------------------------------------------------------------ domains / certificates

    /**
     * The ClusterIssuer created by add-cluster.sh: one Google Trust Services ACME account with a DNS-01 solver
     * selected for the Cloud DNS zone and HTTP-01 for every other host (cert-manager picks per host).
     */
    public static final String DEFAULT_ISSUER = "gts";

    /** HTTPS for the generated default hosts of this env (off until cert-manager is set up on the cluster). */
    public boolean tlsFor(String env) {
        return Boolean.TRUE.equals(clusterFor(env).tls());
    }

    /** True when the host is inside the env cluster's Cloud DNS zone (records + DNS-01 handled automatically). */
    public boolean managedDns(String env, String host) {
        return inZone(clusterFor(env).dnsZone(), host);
    }

    static boolean inZone(String zone, String host) {
        if (zone == null || host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals(zone) || h.endsWith("." + zone);
    }

    /** ClusterIssuer for certificates on this env's cluster (the solver - DNS-01 or HTTP-01 - is chosen by cert-manager). */
    public String issuerFor(String env, String host) {
        String i = clusterFor(env).issuer();
        return i == null ? DEFAULT_ISSUER : i;
    }

    /** IP custom domains must resolve to (null when unknown - the UI then only says "point it at the cluster"). */
    public String ingressIpFor(String env) {
        return clusterFor(env).ingressIp();
    }

    /**
     * Argo Rollouts available on the env's cluster. Workload clusters declare it ({@code rollouts: true});
     * for the hub (fallback layout) an unset flag is detected from the CRD as before.
     */
    public boolean rolloutsFor(String env) {
        Cluster c = clusterFor(env);
        if (c.rollouts() != null) return c.rollouts();
        return inCluster(c) && hubHasRollouts();
    }

    private boolean hubHasRollouts() {
        if (hubRollouts != null && hubCheckedAt.plus(CRD_CACHE).isAfter(Instant.now())) return hubRollouts;
        try {
            hubRollouts = hub.apiextensions().v1().customResourceDefinitions().withName("rollouts.argoproj.io").get() != null;
        } catch (RuntimeException e) {
            log.debug("rollouts CRD check failed: {}", e.getMessage());
            hubRollouts = hubRollouts != null && hubRollouts;   // keep last known value on transient errors
        }
        hubCheckedAt = Instant.now();
        return hubRollouts;
    }
}
