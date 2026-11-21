package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.ClusterRecord;
import com.aio.platform.domain.Environment;
import com.aio.platform.repo.ClusterRecordRepo;
import com.aio.platform.repo.EnvironmentRepo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The delivery environments (dev → staging → prod → ...) and the clusters they run on, from the
 * {@code environments} / {@code clusters} tables. Replaces the former hard-coded {@code GitOpsService.ENVS}.
 *
 * <p>Reads are served from an in-memory snapshot (reloaded after every change and every 30 s, so several
 * platform instances converge). On first start, empty tables are seeded from {@code app.clusters} (or the
 * in-cluster fallback) with dev, staging and prod exactly as they were configured before, so an upgrade
 * changes nothing.
 */
@Service
public class EnvironmentRegistry {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentRegistry.class);

    /** The environments every installation started with; the seed when the table is empty. */
    public static final List<String> DEFAULT_ENVS = List.of("dev", "staging", "prod");
    public static final Set<String> CLOUDS = Set.of("aks", "gke", "eks", "oke", "doks", "onprem", "other");
    public static final String NAME_PATTERN = "[a-z][a-z0-9-]{1,19}";

    /** One environment as the rest of the platform sees it. */
    public record Env(String name, int position, String promoteFrom, String cluster, String namespace,
                      boolean approvalRequired, boolean spotAllowed, boolean prodLike) {}

    private record Snapshot(List<Env> envs, Map<String, Env> byName, Map<String, ClusterRecord> clusters) {
        static Snapshot of(List<Env> envs, List<ClusterRecord> clusters) {
            List<Env> sorted = envs.stream().sorted(Comparator.comparingInt(Env::position)).toList();
            Map<String, Env> by = new LinkedHashMap<>();
            sorted.forEach(e -> by.put(e.name(), e));
            Map<String, ClusterRecord> cs = new LinkedHashMap<>();
            clusters.forEach(c -> cs.put(c.name, c));
            return new Snapshot(sorted, by, cs);
        }
    }

    /** Last loaded snapshot, also for static helpers such as {@link ClusterService#namespace}. */
    private static volatile EnvironmentRegistry current;

    private final EnvironmentRepo envRepo;
    private final ClusterRecordRepo clusterRepo;
    private final AppProperties props;
    private volatile Snapshot snap;

    @Autowired
    public EnvironmentRegistry(EnvironmentRepo envRepo, ClusterRecordRepo clusterRepo, AppProperties props,
                               @org.springframework.beans.factory.annotation.Value("${CLOUD_PROVIDER:other}") String cloud) {
        this.envRepo = envRepo;
        this.clusterRepo = clusterRepo;
        this.props = props;
        seedIfEmpty(cloud);
        reload();
        current = this;
    }

    /** In-memory registry with the seed of the given settings (tests, and tools without a database). */
    private EnvironmentRegistry(AppProperties props, String cloud) {
        this.envRepo = null;
        this.clusterRepo = null;
        this.props = props;
        Seed seed = seed(props, cloud);
        this.snap = Snapshot.of(seed.envs().stream().map(EnvironmentRegistry::view).toList(), seed.clusters());
    }

    public static EnvironmentRegistry fromConfig(AppProperties props, String cloud) {
        return new EnvironmentRegistry(props, cloud);
    }

    /** dev, staging, prod on the in-cluster hub: the layout before any configuration. */
    public static EnvironmentRegistry defaults() {
        return new EnvironmentRegistry(new AppProperties(null, null, null, null, null, null, null, null, null, null, null), "other");
    }

    /** In-memory registry with exactly these rows (tests). */
    public static EnvironmentRegistry of(List<Env> envs, List<ClusterRecord> clusters) {
        EnvironmentRegistry r = defaults();
        r.snap = Snapshot.of(envs, clusters);
        return r;
    }

    static EnvironmentRegistry current() {
        return current;
    }

    // ------------------------------------------------------------------ seeding

    record Seed(List<ClusterRecord> clusters, List<Environment> envs) {}

    /**
     * What today's static configuration means as rows: every configured cluster (or the in-cluster fallback), and
     * dev (from none) → staging (from dev) → prod (from staging, approval, prod-like) on the clusters they map to.
     */
    static Seed seed(AppProperties props, String cloud) {
        List<AppProperties.Cluster> configured = ClusterRegistry.resolve(props);
        Map<String, AppProperties.Cluster> byEnv = ClusterRegistry.index(configured);
        String hubCloud = cloud == null ? "other" : cloud.trim().toLowerCase(Locale.ROOT);
        if (!CLOUDS.contains(hubCloud)) hubCloud = "other";
        List<ClusterRecord> clusters = new ArrayList<>();
        for (AppProperties.Cluster c : configured) {
            ClusterRecord r = new ClusterRecord();
            r.name = c.name();
            r.server = c.server() == null ? ClusterRegistry.IN_CLUSTER_SERVER : c.server();
            r.cloud = ClusterRegistry.IN_CLUSTER_SERVER.equals(r.server) || ClusterRegistry.IN_CLUSTER.equals(r.name) ? hubCloud : "other";
            r.baseDomain = c.baseDomain();
            r.ingressIp = c.ingressIp();
            r.issuer = c.issuer();
            r.dnsZone = c.dnsZone();
            r.tls = c.tls();
            r.rollouts = c.rollouts();
            r.ingressClass = c.ingressClass();
            r.ingressController = c.ingressController();
            r.ingressNamespace = c.ingressNamespace() == null || c.ingressNamespace().isBlank() ? null : c.ingressNamespace().trim();
            r.createdBy = "seed";
            clusters.add(r);
        }
        List<Environment> envs = new ArrayList<>();
        String prev = null;
        int pos = 1;
        for (String name : DEFAULT_ENVS) {
            Environment e = new Environment();
            e.name = name;
            e.position = pos++;
            e.promoteFrom = prev;
            e.cluster = byEnv.get(name).name();
            e.namespace = name;
            e.approvalRequired = "prod".equals(name);
            e.prodLike = "prod".equals(name);
            e.spotAllowed = !"prod".equals(name);
            envs.add(e);
            prev = name;
        }
        return new Seed(clusters, envs);
    }

    private void seedIfEmpty(String cloud) {
        if (envRepo.count() > 0) return;
        Seed seed = seed(props, cloud);
        try {
            for (ClusterRecord c : seed.clusters()) if (!clusterRepo.existsById(c.name)) clusterRepo.save(c);
            // promote_from references an earlier row: save in order
            for (Environment e : seed.envs()) envRepo.saveAndFlush(e);
            log.info("seeded environments {} and clusters {} from configuration", DEFAULT_ENVS,
                    seed.clusters().stream().map(c -> c.name).toList());
        } catch (RuntimeException e) {
            // another instance seeded at the same time: its rows win
            log.info("environment seed skipped: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.environments.reload-ms:30000}", initialDelayString = "${app.environments.reload-ms:30000}")
    public void reload() {
        if (envRepo == null) return;
        try {
            snap = Snapshot.of(envRepo.findAllByOrderByPositionAsc().stream().map(EnvironmentRegistry::view).toList(),
                    clusterRepo.findAll());
        } catch (RuntimeException e) {
            if (snap == null) throw e;
            log.warn("environment reload failed, keeping the last snapshot: {}", e.getMessage());
        }
    }

    static Env view(Environment e) {
        return new Env(e.name, e.position, e.promoteFrom, e.cluster, e.namespace == null || e.namespace.isBlank() ? e.name : e.namespace,
                e.approvalRequired, e.spotAllowed && !e.prodLike, e.prodLike);
    }

    // ------------------------------------------------------------------ reads

    /** Ordered environments. */
    public List<Env> list() {
        return snap.envs();
    }

    /** Ordered environment names (what {@code GitOpsService.ENVS} used to be). */
    public List<String> names() {
        return snap.envs().stream().map(Env::name).toList();
    }

    public boolean exists(String env) {
        return env != null && snap.byName().containsKey(env);
    }

    public Env get(String env) {
        Env e = env == null ? null : snap.byName().get(env);
        if (e == null) throw new IllegalArgumentException("unknown environment: " + env);
        return e;
    }

    public Optional<Env> find(String env) {
        return Optional.ofNullable(env == null ? null : snap.byName().get(env));
    }

    /** The environment images are promoted from into {@code env}; null for the first one (CI deploys there). */
    public String promoteFrom(String env) {
        return get(env).promoteFrom();
    }

    /** Environments promoted from {@code env}, in order. */
    public List<String> promotesTo(String env) {
        return snap.envs().stream().filter(e -> env.equals(e.promoteFrom())).map(Env::name).toList();
    }

    public boolean isProdLike(String env) {
        return find(env).map(Env::prodLike).orElse(false);
    }

    /** Changes (promotion, rollback, sync, scale, domains) need an approver: approval_required or prod-like. */
    public boolean approvalRequired(String env) {
        return find(env).map(e -> e.approvalRequired() || e.prodLike()).orElse(false);
    }

    public boolean spotAllowed(String env) {
        return find(env).map(Env::spotAllowed).orElse(false);
    }

    public Set<String> prodLike() {
        return snap.envs().stream().filter(Env::prodLike).map(Env::name).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** Name of the cluster the environment deploys to. */
    public String clusterFor(String env) {
        return get(env).cluster();
    }

    public String namespaceOf(String env) {
        return find(env).map(Env::namespace).orElse(env);
    }

    /** Policy class of an env for artifact trust: the first is "dev", prod-like ones "prod", the rest "staging". */
    public String trustClass(String env) {
        Env e = get(env);
        if (e.prodLike()) return "prod";
        return e.promoteFrom() == null ? "dev" : "staging";
    }

    // ------------------------------------------------------------------ clusters

    public List<ClusterRecord> clusterRecords() {
        return List.copyOf(snap.clusters().values());
    }

    public Optional<ClusterRecord> cluster(String name) {
        return Optional.ofNullable(name == null ? null : snap.clusters().get(name));
    }

    public List<String> envsOn(String cluster) {
        return snap.envs().stream().filter(e -> e.cluster().equals(cluster)).map(Env::name).toList();
    }

    /** Clusters as {@link AppProperties.Cluster} records, with the envs each one hosts. */
    public List<AppProperties.Cluster> clusters() {
        return snap.clusters().values().stream().map(c -> new AppProperties.Cluster(c.name, c.server, envsOn(c.name),
                c.baseDomain, c.rollouts, c.ingressIp, c.issuer, ClusterRegistry.zone(c.dnsZone), c.tls,
                blank(c.ingressClass), blank(c.ingressController), blank(c.ingressNamespace))).toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------ writes (EnvironmentService validates first)

    // Without repositories (in-memory registry, tests) the snapshot itself is changed.

    Environment save(Environment e) {
        if (envRepo == null) {
            List<Env> envs = new ArrayList<>(snap.envs().stream().filter(x -> !x.name().equals(e.name)).toList());
            envs.add(view(e));
            snap = Snapshot.of(envs, clusterRecords());
            return e;
        }
        Environment saved = envRepo.saveAndFlush(e);
        reload();
        return saved;
    }

    void delete(String env) {
        if (envRepo == null) {
            snap = Snapshot.of(snap.envs().stream().filter(x -> !x.name().equals(env)).toList(), clusterRecords());
            return;
        }
        envRepo.deleteById(env);
        envRepo.flush();
        reload();
    }

    ClusterRecord save(ClusterRecord c) {
        if (clusterRepo == null) {
            List<ClusterRecord> cs = new ArrayList<>(clusterRecords().stream().filter(x -> !x.name.equals(c.name)).toList());
            cs.add(c);
            snap = Snapshot.of(snap.envs(), cs);
            return c;
        }
        ClusterRecord saved = clusterRepo.saveAndFlush(c);
        reload();
        return saved;
    }

    void deleteCluster(String name) {
        if (clusterRepo == null) {
            snap = Snapshot.of(snap.envs(), clusterRecords().stream().filter(x -> !x.name.equals(name)).toList());
            return;
        }
        clusterRepo.deleteById(name);
        clusterRepo.flush();
        reload();
    }

    int nextPosition() {
        return snap.envs().stream().mapToInt(Env::position).max().orElse(0) + 1;
    }
}
