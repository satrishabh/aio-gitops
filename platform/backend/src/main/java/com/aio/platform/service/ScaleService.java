package com.aio.platform.service;

import com.aio.platform.domain.Project;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scale an environment down (stop, 0 replicas) or back up. The change is a GitOps commit to the env's values
 * file, so Argo CD stays the single source of truth; a scaled-down env keeps receiving new image tags but
 * stays stopped until someone starts it again.
 */
@Service
public class ScaleService {
    private static final Logger log = LoggerFactory.getLogger(ScaleService.class);
    static final int MAX_REPLICAS = 5;

    private final GitOpsService gitops;
    private final ArgoCdService argo;
    private final PromotionService approvals;
    private final AuditService audit;

    public ScaleService(GitOpsService gitops, ArgoCdService argo, PromotionService approvals, AuditService audit) {
        this.gitops = gitops;
        this.argo = argo;
        this.approvals = approvals;
        this.audit = audit;
    }

    static void validate(String env, int replicas, boolean approver) {
        if (!GitOpsService.ENVS.contains(env)) throw new IllegalArgumentException("unknown environment: " + env);
        if (replicas < 0 || replicas > MAX_REPLICAS)
            throw new IllegalArgumentException("replicas must be between 0 and " + MAX_REPLICAS);
        if ("prod".equals(env) && !approver) throw new SecurityException("only approvers can scale prod");
    }

    public Map<String, Object> scale(Project p, String env, int replicas, String actor) {
        validate(env, replicas, approvals.isApprover(actor));
        boolean changed = gitops.setReplicas(p, env, replicas,
                "platform: scale " + p.name + " " + env + " to " + replicas + " (by " + actor + ")");
        if (changed) syncSoon(p, env);
        audit.record(actor, "env.scale", p.name + "/" + env, replicas == 0 ? "scaled down (stopped)" : "replicas=" + replicas);
        return Map.of("environment", env, "replicas", replicas, "changed", changed);
    }

    /** Stop or start every environment that has a real image; prod is skipped for non-approvers. */
    public Map<String, Object> scaleAll(Project p, int replicas, String actor) {
        boolean approver = approvals.isApprover(actor);
        List<String> done = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        for (String env : GitOpsService.ENVS) {
            if ("prod".equals(env) && !approver) { skipped.put(env, "approvers only"); continue; }
            String tag = gitops.readTag(p, env);
            if (tag == null || GitOpsService.PLACEHOLDER_TAG.equals(tag)) { skipped.put(env, "nothing deployed"); continue; }
            try {
                scale(p, env, replicas, actor);
                done.add(env);
            } catch (Exception e) {
                skipped.put(env, e.getMessage());
            }
        }
        audit.record(actor, "project.scale", p.name, (replicas == 0 ? "stopped " : "started ") + done + (skipped.isEmpty() ? "" : " skipped " + skipped));
        return Map.of("replicas", replicas, "applied", done, "skipped", skipped);
    }

    /** Ask Argo CD to pick up the commit now instead of on its next poll. Failures are non-fatal (auto-sync catches up). */
    private void syncSoon(Project p, String env) {
        String app = ArgoCdService.appName(p, env);
        try {
            argo.getApp(app, true);
            argo.sync(app);
        } catch (Exception e) {
            log.info("sync after scale of {} deferred to auto-sync: {}", app, e.getMessage());
        }
    }
}
