package com.aio.platform.web;

import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.service.ArgoCdService;
import com.aio.platform.service.AuditService;
import com.aio.platform.service.ClusterRegistry;
import com.aio.platform.service.ClusterService;
import com.aio.platform.service.GitOpsService;
import com.aio.platform.service.J;
import com.aio.platform.service.PromotionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/**
 * The CD view of one environment: the Argo CD Application (sync / health / last operation / history),
 * its live resource tree, pod logs, and the editable parts - sync policy and the GitOps values file.
 * Changes to prod are approver-only, like promotions.
 */
@RestController
@RequestMapping("/api/projects/{id}/environments/{env}/cd")
public class CdController {
    private final ProjectRepo projects;
    private final ArgoCdService argo;
    private final GitOpsService gitops;
    private final PromotionService promotions;
    private final AuditService audit;
    private final ClusterRegistry clusters;

    public CdController(ProjectRepo projects, ArgoCdService argo, GitOpsService gitops, PromotionService promotions,
                        AuditService audit, ClusterRegistry clusters) {
        this.projects = projects;
        this.argo = argo;
        this.gitops = gitops;
        this.promotions = promotions;
        this.audit = audit;
        this.clusters = clusters;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable Long id, @PathVariable String env) {
        Project p = project(id, env);
        String name = ArgoCdService.appName(p, env);
        Map<String, Object> app = argo.getApp(name, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", p);
        out.put("env", env);
        out.put("appName", name);
        out.put("namespace", ClusterService.namespace(p, env));
        out.put("cluster", clusters.clusterFor(env).name());
        out.put("exists", app != null);
        if (app == null) return out;
        // Differs from "cluster" until a move is applied (prod moves only on an approver's Repair)
        out.put("currentCluster", argo.currentCluster(app));

        Object spec = app.get("spec"), status = app.get("status");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("syncStatus", J.str(status, "sync", "status"));
        s.put("revision", J.str(status, "sync", "revision"));
        s.put("healthStatus", J.str(status, "health", "status"));
        s.put("healthMessage", J.str(status, "health", "message"));
        s.put("images", J.get(status, "summary", "images"));
        s.put("reconciledAt", J.str(status, "reconciledAt"));
        s.put("targetRevision", J.str(spec, "source", "targetRevision"));
        s.put("repoUrl", J.str(spec, "source", "repoURL"));
        s.put("path", J.str(spec, "source", "path"));
        s.put("valueFiles", J.get(spec, "source", "helm", "valueFiles"));
        out.put("app", s);

        Object automated = J.get(spec, "syncPolicy", "automated");
        out.put("syncPolicy", Map.of(
                "automated", automated != null,
                "prune", Boolean.TRUE.equals(J.get(automated, "prune")),
                "selfHeal", Boolean.TRUE.equals(J.get(automated, "selfHeal"))));

        Object op = J.get(status, "operationState");
        if (op != null) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("phase", J.str(op, "phase"));
            o.put("message", J.str(op, "message"));
            o.put("startedAt", J.str(op, "startedAt"));
            o.put("finishedAt", J.str(op, "finishedAt"));
            o.put("revision", J.str(op, "syncResult", "revision"));
            o.put("initiatedBy", J.str(op, "operation", "initiatedBy", "username") != null
                    ? J.str(op, "operation", "initiatedBy", "username")
                    : Boolean.TRUE.equals(J.get(op, "operation", "initiatedBy", "automated")) ? "auto-sync" : null);
            o.put("resources", J.list(op, "syncResult", "resources").stream().map(r -> pick(r,
                    "kind", "name", "namespace", "status", "message", "syncPhase", "hookPhase")).toList());
            out.put("operation", o);
        }

        List<Map<String, Object>> history = new ArrayList<>(J.list(status, "history").stream().map(h -> pick(h,
                "id", "revision", "deployedAt", "deployStartedAt")).toList());
        java.util.Collections.reverse(history);   // newest first
        out.put("history", history);
        out.put("conditions", J.list(status, "conditions").stream().map(c -> pick(c, "type", "message")).toList());
        out.put("resources", J.list(status, "resources").stream().map(r -> {
            Map<String, Object> m = pick(r, "kind", "name", "namespace", "status");
            m.put("health", J.str(r, "health", "status"));
            m.put("healthMessage", J.str(r, "health", "message"));
            return m;
        }).toList());

        Map<String, Object> tree = argo.resourceTree(name);
        out.put("nodes", tree == null ? List.of() : J.list(tree, "nodes").stream().map(n -> {
            Map<String, Object> m = pick(n, "kind", "name", "namespace", "uid", "createdAt", "images");
            m.put("health", J.str(n, "health", "status"));
            m.put("healthMessage", J.str(n, "health", "message"));
            m.put("parents", J.list(n, "parentRefs").stream().map(pr -> J.str(pr, "uid")).toList());
            m.put("info", J.list(n, "info").stream().map(i -> pick(i, "name", "value")).toList());
            return m;
        }).toList());
        return out;
    }

    @PostMapping("/sync")
    public Map<String, String> sync(@PathVariable Long id, @PathVariable String env, @AuthenticationPrincipal OAuth2User user) {
        Project p = project(id, env);
        String actor = guardProd(env, user, "sync prod");
        argo.sync(ArgoCdService.appName(p, env));
        audit.record(actor, "cd.sync", p.name + "/" + env, null);
        return Map.of("status", "syncing");
    }

    @PostMapping("/refresh")
    public Map<String, String> refresh(@PathVariable Long id, @PathVariable String env) {
        argo.hardRefresh(ArgoCdService.appName(project(id, env), env));
        return Map.of("status", "refreshed");
    }

    public record SyncPolicyRequest(Boolean automated, Boolean prune, Boolean selfHeal) {}

    @PutMapping("/sync-policy")
    public Map<String, String> syncPolicy(@PathVariable Long id, @PathVariable String env, @RequestBody SyncPolicyRequest req,
                                          @AuthenticationPrincipal OAuth2User user) {
        Project p = project(id, env);
        String actor = guardProd(env, user, "change the prod sync policy");
        if (req == null || req.automated() == null) throw new IllegalArgumentException("automated is required");
        boolean prune = Boolean.TRUE.equals(req.prune()), selfHeal = Boolean.TRUE.equals(req.selfHeal());
        argo.setAutoSync(ArgoCdService.appName(p, env), req.automated(), prune, selfHeal);
        audit.record(actor, "cd.policy", p.name + "/" + env,
                req.automated() ? "auto-sync on (prune=" + prune + ", selfHeal=" + selfHeal + ")" : "auto-sync off");
        return Map.of("status", "updated");
    }

    @GetMapping("/values")
    public GitOpsService.RawValues values(@PathVariable Long id, @PathVariable String env) {
        return gitops.readRaw(project(id, env), env);
    }

    public record ValuesRequest(String yaml, String sha, String message) {}

    /** Commits an edited values file to the GitOps repo; Argo CD picks it up on the next sync. */
    @PutMapping("/values")
    public GitOpsService.RawValues saveValues(@PathVariable Long id, @PathVariable String env, @RequestBody ValuesRequest req,
                                              @AuthenticationPrincipal OAuth2User user) {
        Project p = project(id, env);
        String actor = guardProd(env, user, "edit prod values");
        if (req == null) throw new IllegalArgumentException("yaml is required");
        String msg = req.message() == null || req.message().isBlank() ? "edit values" : req.message().trim();
        if (msg.length() > 200) msg = msg.substring(0, 200);
        gitops.writeRaw(p, env, req.yaml(), req.sha(), "platform: " + p.name + " " + env + " - " + msg + " (by " + actor + ")");
        audit.record(actor, "cd.values", p.name + "/" + env, msg);
        return gitops.readRaw(p, env);
    }

    /**
     * Pod logs, read through Argo CD (it holds the workload-cluster credentials). Limited to pods Argo CD reports
     * for this app, so no other namespace's or project's pods can be read.
     */
    @GetMapping(value = "/pods/{pod}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable Long id, @PathVariable String env, @PathVariable String pod,
                       @RequestParam(required = false) String container, @RequestParam(defaultValue = "false") boolean previous) {
        Project p = project(id, env);
        String ns = ClusterService.namespace(p, env);
        String app = ArgoCdService.appName(p, env);
        Map<String, Object> tree = argo.resourceTree(app);
        boolean owned = tree != null && J.list(tree, "nodes").stream().anyMatch(n ->
                "Pod".equals(J.str(n, "kind")) && pod.equals(J.str(n, "name")) && ns.equals(J.str(n, "namespace")));
        if (!owned) throw new NoSuchElementException(pod);
        if (container != null && !container.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) throw new IllegalArgumentException("invalid container name");
        try {
            String text = argo.logs(app, ns, pod, container, previous, 3000);
            return text.isEmpty() ? "(no output yet)" : text;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return "(logs not available: HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString() + ")";
        }
    }

    private Project project(Long id, String env) {
        if (!GitOpsService.ENVS.contains(env)) throw new IllegalArgumentException("unknown environment: " + env);
        return projects.findById(id).orElseThrow();
    }

    private String guardProd(String env, OAuth2User user, String what) {
        String actor = Me.login(user);
        if ("prod".equals(env) && !promotions.isApprover(actor)) throw new SecurityException("only approvers can " + what);
        return actor;
    }

    private static Map<String, Object> pick(Map<String, Object> src, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, src.get(k));
        return m;
    }
}
