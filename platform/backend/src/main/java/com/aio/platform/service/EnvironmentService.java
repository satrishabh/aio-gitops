package com.aio.platform.service;

import com.aio.platform.domain.Environment;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.DomainRepo;
import com.aio.platform.repo.ProjectRepo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Adding and removing environments (admin). Adding one prepares its namespace on its cluster with the same baseline
 * as the others, then creates every project's {@code values-<env>.yaml} (placeholder tag, 0 replicas) and its Argo CD
 * Application, so the new stop appears on every project's delivery track, empty until something is promoted there.
 */
@Service
public class EnvironmentService {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final EnvironmentRegistry environments;
    private final ClusterRegistry clusters;
    private final ClusterService clusterService;
    private final ClusterAccess access;
    private final GitOpsService gitops;
    private final ArgoCdService argo;
    private final ProjectRepo projects;
    private final DomainRepo domains;
    private final AuditService audit;

    public EnvironmentService(EnvironmentRegistry environments, ClusterRegistry clusters, ClusterService clusterService,
                              ClusterAccess access, GitOpsService gitops, ArgoCdService argo, ProjectRepo projects,
                              DomainRepo domains, AuditService audit) {
        this.environments = environments;
        this.clusters = clusters;
        this.clusterService = clusterService;
        this.access = access;
        this.gitops = gitops;
        this.argo = argo;
        this.projects = projects;
        this.domains = domains;
        this.audit = audit;
    }

    /** Add environment form. Null promoteFrom = after the last env; null approval = on when promoted from a prod-like env. */
    public record AddRequest(String name, String cluster, String namespace, String promoteFrom, Boolean approvalRequired,
                             Boolean prodLike, Boolean spotAllowed) {}

    /** One step of an add/remove, shown in the dialog. */
    public record Step(String step, boolean ok, String detail) {}

    public record Result(EnvironmentRegistry.Env environment, List<Step> steps) {}

    /** Validates and normalises the request into a row (no side effects). */
    Environment plan(AddRequest req) {
        if (req == null || req.name() == null) throw new IllegalArgumentException("name is required");
        String name = req.name().trim();
        if (!name.matches(EnvironmentRegistry.NAME_PATTERN))
            throw new IllegalArgumentException("name must match " + EnvironmentRegistry.NAME_PATTERN + " (lowercase, 2-20 characters)");
        if (environments.exists(name)) throw new IllegalStateException("environment " + name + " already exists");
        String cluster = req.cluster() == null ? "" : req.cluster().trim();
        if (environments.cluster(cluster).isEmpty()) throw new IllegalArgumentException("unknown cluster: " + cluster);
        String ns = req.namespace() == null || req.namespace().isBlank() ? name : req.namespace().trim();
        if (!ns.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")) throw new IllegalArgumentException("invalid namespace: " + ns);
        if (List.of("kube-system", "kube-public", "kube-node-lease", "default", BootstrapScript.NAMESPACE, "argocd").contains(ns))
            throw new IllegalArgumentException("namespace " + ns + " is reserved");
        for (var e : environments.list())
            if (e.cluster().equals(cluster) && e.namespace().equals(ns))
                throw new IllegalStateException("namespace " + ns + " on " + cluster + " is already used by " + e.name());
        List<String> names = environments.names();
        String from = req.promoteFrom() == null || req.promoteFrom().isBlank()
                ? (names.isEmpty() ? null : names.get(names.size() - 1)) : req.promoteFrom().trim();
        if (from != null && !environments.exists(from)) throw new IllegalArgumentException("unknown environment to promote from: " + from);
        if (from == null) throw new IllegalArgumentException("a new environment must be promoted from an existing one");
        Environment e = new Environment();
        e.name = name;
        e.position = environments.nextPosition();
        e.promoteFrom = from;
        e.cluster = cluster;
        e.namespace = ns;
        e.prodLike = Boolean.TRUE.equals(req.prodLike());
        e.approvalRequired = req.approvalRequired() != null ? req.approvalRequired() : (e.prodLike || environments.isProdLike(from));
        e.spotAllowed = !e.prodLike && Boolean.TRUE.equals(req.spotAllowed());
        return e;
    }

    public Result add(AddRequest req, String actor) {
        Environment row = plan(req);
        environments.save(row);
        String env = row.name;
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("environment saved", true, env + " after " + row.promoteFrom + " on " + row.cluster));
        try {
            clusterService.ensureNamespace(env);
            steps.add(new Step("namespace " + row.namespace + " on " + row.cluster, true,
                    "Pod Security labels, LimitRange, NetworkPolicies, ghcr-pull"));
        } catch (RuntimeException e) {
            steps.add(new Step("namespace " + row.namespace + " on " + row.cluster, false, ClusterAccess.short_(e)));
        }
        try {
            access.setNamespaces(row.cluster, namespacesOn(row.cluster));
            boolean allowed = access.allowDestination(row.namespace);
            steps.add(new Step("Argo CD destination", allowed, allowed ? "project allows namespace " + row.namespace
                    : "add namespace " + row.namespace + " to the Argo CD project's destinations by hand"));
        } catch (RuntimeException e) {
            steps.add(new Step("Argo CD destination", false, ClusterAccess.short_(e)));
        }
        int ok = 0;
        for (Project p : projects.findAll()) {
            try {
                gitops.initProject(p);   // creates only missing values files: the new env's
                argo.upsertApp(p, env);
                ok++;
            } catch (RuntimeException e) {
                steps.add(new Step(p.name + " " + env, false, ClusterAccess.short_(e)));
                log.warn("adding {} to {} failed: {}", env, p.name, e.getMessage());
            }
        }
        steps.add(new Step("projects", true, ok + " project" + (ok == 1 ? "" : "s") + " got values-" + env + ".yaml and an Argo CD app"));
        audit.record(actor, "environment.add", env, "cluster=" + row.cluster + " ns=" + row.namespace + " from=" + row.promoteFrom
                + " approval=" + row.approvalRequired + " prodLike=" + row.prodLike);
        return new Result(environments.get(env), steps);
    }

    /** Why the environment cannot be removed, or null. */
    String removalBlocker(String env) {
        var e = environments.get(env);
        if (e.promoteFrom() == null) return env + " is the first environment (CI deploys there)";
        if (EnvironmentRegistry.DEFAULT_ENVS.contains(env)) return env + " is built in";
        List<String> next = environments.promotesTo(env);
        if (!next.isEmpty()) return String.join(", ", next) + " is promoted from " + env + "; remove that first";
        List<String> running = new ArrayList<>();
        for (Project p : projects.findAll()) {
            Integer replicas;
            try {
                replicas = GitOpsService.extractReplicas(gitops.readRaw(p, env).yaml());
            } catch (IllegalStateException missing) {
                continue;   // no values file: nothing deployed
            }
            if (replicas != null && replicas > 0) running.add(p.name);
            if (!domains.findByProjectIdAndEnvOrderByHostAscPathAsc(p.id, env).isEmpty())
                return p.name + " has custom domains in " + env + "; remove them first";
        }
        if (!running.isEmpty()) return "still running in " + env + ": " + String.join(", ", running) + " (scale them to 0 first)";
        return null;
    }

    public Result remove(String env, String actor) {
        String blocker = removalBlocker(env);
        if (blocker != null) throw new IllegalStateException("cannot remove " + env + ": " + blocker);
        var e = environments.get(env);
        List<Step> steps = new ArrayList<>();
        for (Project p : projects.findAll()) {
            try {
                argo.deleteApp(ArgoCdService.appName(p, env));
                gitops.deleteEnvFile(p, env);
            } catch (RuntimeException ex) {
                steps.add(new Step(p.name + " " + env, false, ClusterAccess.short_(ex)));
            }
        }
        environments.delete(env);
        try {
            access.setNamespaces(e.cluster(), namespacesOn(e.cluster()));
        } catch (RuntimeException ex) {
            steps.add(new Step("Argo CD cluster namespaces", false, ClusterAccess.short_(ex)));
        }
        steps.add(new Step("environment removed", true, "namespace " + e.namespace() + " on " + e.cluster() + " was left in place"));
        audit.record(actor, "environment.remove", env, "cluster=" + e.cluster());
        return new Result(e, steps);
    }

    /** Actor recorded for the automatic re-apply. */
    static final String AUTO_ACTOR = "aio-platform";

    /** Automatic re-apply of cluster settings; on by default, independent of app.runtime.workers-enabled. */
    @org.springframework.beans.factory.annotation.Value("${app.environments.auto-cluster-settings:true}")
    boolean autoClusterSettings = true;

    /**
     * Keeps every app's cluster-owned ingress settings (class, controller, the generated {@code <app>-<env>.<domain>}
     * host) in line with its environment's cluster, so nobody has to press "Re-apply cluster settings" after a
     * cluster changes or an app was created under an old cluster. Runs shortly after startup and then every 30
     * minutes; it commits only files that differ, never touches custom hosts or {@code routes:}, and an env whose
     * cluster is unreachable only logs. Prod is changed in git like the others but, as always, waits for an
     * approver's sync.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.environments.auto-cluster-settings-ms:1800000}",
            initialDelayString = "${app.environments.auto-cluster-settings-initial-ms:120000}")
    public void autoReapplyClusterSettings() {
        if (!autoClusterSettings) return;
        for (String env : environments.names()) {
            try {
                Map<String, Object> r = reapplyClusterSettings(env, false, AUTO_ACTOR);
                long changed = (long) r.get("changed");
                if (changed > 0) log.info("cluster settings re-applied to {} values file(s) in {}", changed, env);
                if (r.get("errors") instanceof List<?> errs && !errs.isEmpty())
                    log.warn("cluster settings for {} not re-applied for: {}", env, errs);
            } catch (RuntimeException e) {
                log.warn("automatic re-apply of cluster settings for {} failed: {}", env, ClusterAccess.short_(e));
            }
        }
    }

    /** Re-applies the cluster's ingress settings to every project's values of one env (dry run: diff only). */
    public Map<String, Object> reapplyClusterSettings(String env, boolean dryRun, String actor) {
        environments.get(env);
        List<GitOpsService.ClusterSettingsChange> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Project p : projects.findAll()) {
            try {
                changes.add(gitops.reapplyClusterSettings(p, env, dryRun,
                        "platform: re-apply " + clusters.clusterFor(env).name() + " ingress settings to " + p.name + " " + env + " (by " + actor + ")"));
            } catch (RuntimeException e) {
                errors.add(p.name + ": " + ClusterAccess.short_(e));
            }
        }
        long changed = changes.stream().filter(GitOpsService.ClusterSettingsChange::changed).count();
        // the automatic run audits only when it actually changed something, so the log isn't flooded every 30 minutes
        if (!dryRun && (changed > 0 || !AUTO_ACTOR.equals(actor)))
            audit.record(actor, "environment.reapply", env, changed + " values file(s) updated");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("environment", env);
        out.put("cluster", clusters.clusterFor(env).name());
        out.put("dryRun", dryRun);
        out.put("changed", changed);
        out.put("changes", changes);
        out.put("errors", errors);
        return out;
    }

    private List<String> namespacesOn(String cluster) {
        return environments.list().stream().filter(x -> x.cluster().equals(cluster)).map(EnvironmentRegistry.Env::namespace).distinct().toList();
    }
}
