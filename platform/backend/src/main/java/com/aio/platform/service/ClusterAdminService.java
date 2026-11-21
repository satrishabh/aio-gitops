package com.aio.platform.service;

import com.aio.platform.domain.ClusterRecord;
import io.fabric8.kubernetes.api.model.Secret;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Clusters → Add cluster / Rotate token / Remove, and the 5-minute health check. Adding: (1) the admin describes the
 * cluster, (2) runs the generated bootstrap script against it, (3) pastes the printed token + CA; the platform writes
 * the Argo CD cluster Secret, tests the connection as that token and stores the cluster row (no credentials).
 */
@Service
public class ClusterAdminService {
    private static final Logger log = LoggerFactory.getLogger(ClusterAdminService.class);
    static final String NAME = "[a-z0-9]([-a-z0-9]{0,38}[a-z0-9])?";
    private static final String DNS = "(?=.{1,253}$)([a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?\\.)+[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?";

    private final EnvironmentRegistry environments;
    private final ClusterAccess access;
    private final AuditService audit;
    private final ClusterRegistry clusters;
    private final boolean healthEnabled;

    public ClusterAdminService(EnvironmentRegistry environments, ClusterAccess access, AuditService audit, ClusterRegistry clusters,
                               @Value("${app.clusters-health.enabled:true}") boolean healthEnabled) {
        this.environments = environments;
        this.access = access;
        this.audit = audit;
        this.clusters = clusters;
        this.healthEnabled = healthEnabled;
    }

    /** Step 1 fields, plus step 3's credentials (token + caData, or the bootstrap JSON line). */
    public record ClusterRequest(String name, String cloud, String server, String ingressController, String ingressClass,
                                 String ingressNamespace, String ingressIp, String baseDomain, String issuer, Boolean tls,
                                 String dnsZone, Boolean rollouts, String token, String caData, String bootstrapJson) {}

    /** Outcome of connect / rotate: every check, and whether the cluster was saved. */
    public record ConnectResult(boolean ok, String cluster, List<ClusterAccess.Check> checks, String message) {}

    /** Validates step 1 into a row (no side effects). */
    static ClusterRecord describe(ClusterRequest r) {
        if (r == null || r.name() == null || !r.name().trim().matches(NAME))
            throw new IllegalArgumentException("name: lowercase letters, digits and '-', up to 40 characters");
        ClusterRecord c = new ClusterRecord();
        c.name = r.name().trim();
        c.cloud = r.cloud() == null ? "other" : r.cloud().trim().toLowerCase(Locale.ROOT);
        if (!EnvironmentRegistry.CLOUDS.contains(c.cloud)) throw new IllegalArgumentException("cloud must be one of " + EnvironmentRegistry.CLOUDS);
        c.server = r.server() == null ? "" : r.server().trim().replaceAll("/+$", "");
        if (!c.server.matches("https://[A-Za-z0-9.:\\[\\]-]+(/[A-Za-z0-9._/-]*)?")) throw new IllegalArgumentException("API server must be an https:// URL");
        if (ClusterRegistry.IN_CLUSTER_SERVER.equals(c.server)) throw new IllegalArgumentException("the hub is already registered");
        c.ingressController = ClusterRegistry.controller(c.name, r.ingressController());
        if (c.ingressController == null) c.ingressController = "nginx";
        c.ingressClass = opt(r.ingressClass(), "[a-z0-9]([-a-z0-9.]{0,61}[a-z0-9])?", "ingress class");
        if (c.ingressClass == null) c.ingressClass = c.ingressController;
        c.ingressNamespace = opt(r.ingressNamespace(), "[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?", "ingress namespace");
        if (c.ingressNamespace == null) c.ingressNamespace = "traefik".equals(c.ingressController) ? "traefik" : "ingress-nginx";
        c.ingressIp = opt(r.ingressIp(), "[0-9a-fA-F:.]{2,45}", "ingress IP");
        c.baseDomain = opt(r.baseDomain() == null ? null : r.baseDomain().toLowerCase(Locale.ROOT), DNS, "base domain");
        c.issuer = opt(r.issuer(), "[a-z0-9]([-a-z0-9.]{0,61}[a-z0-9])?", "issuer");
        c.dnsZone = ClusterRegistry.zone(opt(r.dnsZone() == null ? null : r.dnsZone().toLowerCase(Locale.ROOT), DNS + "\\.?", "DNS zone"));
        c.tls = Boolean.TRUE.equals(r.tls());
        c.rollouts = Boolean.TRUE.equals(r.rollouts());
        return c;
    }

    private static String opt(String v, String pattern, String what) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        if (!s.matches(pattern)) throw new IllegalArgumentException("invalid " + what + ": " + s);
        return s;
    }

    /** Step 2: the bootstrap script for the described cluster (contains no secrets). */
    public Map<String, String> bootstrap(ClusterRequest r, boolean rotate) {
        ClusterRecord c = rotate ? existing(r == null ? null : r.name()) : describe(r);
        if (!rotate && environments.cluster(c.name).isPresent()) throw new IllegalStateException("cluster " + c.name + " already exists");
        return Map.of("fileName", BootstrapScript.fileName(c.name), "script", BootstrapScript.render(c.name, c.cloud, c.server));
    }

    private ClusterRecord existing(String name) {
        return environments.cluster(name).orElseThrow(() -> new java.util.NoSuchElementException("unknown cluster: " + name));
    }

    static ClusterAccess.Credentials credentials(ClusterRequest r, String cluster) {
        Map<String, String> line = ClusterAccess.parseBootstrapLine(r.bootstrapJson());
        if (line.containsKey("cluster") && !line.get("cluster").equals(cluster))
            throw new IllegalArgumentException("that line was printed for cluster " + line.get("cluster") + ", not " + cluster);
        String token = line.getOrDefault("token", r.token());
        String ca = line.getOrDefault("caData", r.caData());
        return new ClusterAccess.Credentials(token, ca);
    }

    /** Step 3 for a new cluster. */
    public ConnectResult connect(ClusterRequest r, String actor) {
        ClusterRecord c = describe(r);
        if (environments.cluster(c.name).isPresent()) throw new IllegalStateException("cluster " + c.name + " already exists");
        ClusterAccess.Credentials creds = credentials(r, c.name);
        ConnectResult res = install(c, creds, List.of());
        if (res.ok()) {
            c.createdBy = actor;
            c.status = ClusterRecord.CONNECTED;
            c.lastCheckAt = Instant.now();
            environments.save(c);
            audit.record(actor, "cluster.add", c.name, c.cloud + " " + c.server);
        }
        return res;
    }

    /** Step 3 again for an existing portal-added cluster: new token (e.g. after revoking the old one). */
    public ConnectResult rotate(String name, ClusterRequest r, String actor) {
        ClusterRecord c = existing(name);
        if (clusters.inCluster(clusters.clusters().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow()))
            throw new IllegalArgumentException("the hub uses the platform's own service account; nothing to rotate");
        ClusterAccess.Credentials creds = credentials(r == null ? new ClusterRequest(name, null, null, null, null, null, null, null, null, null, null, null, null, null, null) : r, name);
        List<String> namespaces = environments.list().stream().filter(e -> e.cluster().equals(name)).map(EnvironmentRegistry.Env::namespace).toList();
        ConnectResult res = install(c, creds, namespaces);
        if (res.ok()) {
            c.status = ClusterRecord.CONNECTED;
            c.lastError = null;
            c.lastCheckAt = Instant.now();
            environments.save(c);
            audit.record(actor, "cluster.rotate", name, "token replaced");
        }
        return res;
    }

    /** Writes the Argo CD Secret, tests as the token; on a failed API check puts the previous Secret back. */
    private ConnectResult install(ClusterRecord c, ClusterAccess.Credentials creds, List<String> namespaces) {
        creds.validate();
        List<ClusterAccess.Check> checks = new ArrayList<>(access.testKubernetes(c.server, creds));
        boolean apiOk = !checks.isEmpty() && Boolean.TRUE.equals(checks.get(0).ok());
        boolean allAllowed = checks.stream().noneMatch(x -> Boolean.FALSE.equals(x.ok()));
        if (!apiOk || !allAllowed)
            return new ConnectResult(false, c.name, checks, apiOk ? "the token lacks some rights: re-run the bootstrap script" : "the cluster is not reachable with this token");
        Secret previous = access.upsertSecret(c.name, c.server, creds, namespaces);
        checks.add(new ClusterAccess.Check("Argo CD cluster Secret", true, "argocd/" + ClusterAccess.secretName(c.name)));
        ClusterAccess.Check argo = access.testArgo(c.name);
        checks.add(argo);
        if (Boolean.FALSE.equals(argo.ok())) {
            access.restoreSecret(c.name, previous);
            return new ConnectResult(false, c.name, checks, "Argo CD cannot connect: " + argo.detail());
        }
        return new ConnectResult(true, c.name, checks, "connected");
    }

    /** Refused while an environment points to the cluster; deletes the Argo CD cluster Secret AIO created. */
    public Map<String, Object> remove(String name, String actor) {
        existing(name);
        List<String> envs = environments.envsOn(name);
        if (!envs.isEmpty()) throw new IllegalStateException("cluster " + name + " still hosts " + String.join(", ", envs) + "; remove those environments first");
        boolean secret = access.deleteSecret(name);
        environments.deleteCluster(name);
        audit.record(actor, "cluster.remove", name, secret ? "Argo CD cluster Secret deleted" : "no AIO-managed Argo CD Secret");
        return Map.of("removed", name, "argoSecretDeleted", secret,
                "hint", "Revoke the token on the cluster too: bash " + BootstrapScript.fileName(name) + " --revoke");
    }

    /** Health check now (also scheduled every 5 minutes). */
    public ClusterRecord check(String name) {
        ClusterRecord c = existing(name);
        boolean hub = ClusterRegistry.IN_CLUSTER_SERVER.equals(c.server) || ClusterRegistry.IN_CLUSTER.equals(c.name);
        String err = access.probe(name, hub);
        c.status = err == null ? ClusterRecord.CONNECTED : ClusterRecord.UNREACHABLE;
        c.lastError = err;
        c.lastCheckAt = Instant.now();
        return environments.save(c);
    }

    @Scheduled(fixedDelayString = "${app.clusters-health.interval-ms:300000}", initialDelayString = "${app.clusters-health.initial-ms:60000}")
    public void checkAll() {
        if (!healthEnabled) return;
        for (ClusterRecord c : environments.clusterRecords()) {
            try {
                ClusterRecord after = check(c.name);
                if (!after.status.equals(c.status)) log.info("cluster {} is now {}{}", c.name, after.status,
                        after.lastError == null ? "" : ": " + after.lastError);
            } catch (RuntimeException e) {
                log.warn("health check of cluster {} failed: {}", c.name, e.getMessage());   // never blocks the rest
            }
        }
    }
}
