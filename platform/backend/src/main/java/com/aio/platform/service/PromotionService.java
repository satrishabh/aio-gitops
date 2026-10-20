package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Deployment;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.PromotionRepo;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * dev -> staging is applied immediately (auto-sync). Anything that touches prod
 * (promotion or rollback) becomes a PENDING request that an approver must approve,
 * after which the tag is committed and the prod Application is synced.
 */
@Service
public class PromotionService {
    private static final Set<String> VALID = Set.of("dev>staging", "staging>prod");

    private final PromotionRepo promotions;
    private final GitOpsService gitops;
    private final ArgoCdService argo;
    private final AuditService audit;
    private final AppProperties props;

    public PromotionService(PromotionRepo promotions, GitOpsService gitops, ArgoCdService argo,
                            AuditService audit, AppProperties props) {
        this.promotions = promotions;
        this.gitops = gitops;
        this.argo = argo;
        this.audit = audit;
        this.props = props;
    }

    public Promotion promote(Project p, String from, String to, String actor) {
        if (!VALID.contains(from + ">" + to)) throw new IllegalArgumentException("allowed: dev->staging, staging->prod");
        String tag = gitops.readTag(p, from);
        if (tag == null || "0.0.0".equals(tag)) throw new IllegalArgumentException(from + " has no deployed image yet");
        return request(p, from, to, tag, "PROMOTE", actor);
    }

    public Promotion rollback(Project p, String env, Deployment target, String actor) {
        if (!GitOpsService.ENVS.contains(env)) throw new IllegalArgumentException("unknown env");
        if (target.imageTag == null) throw new IllegalArgumentException("image tag unknown for that deployment");
        return request(p, null, env, target.imageTag, "ROLLBACK", actor);
    }

    private Promotion request(Project p, String from, String to, String tag, String kind, String actor) {
        Promotion pr = new Promotion();
        pr.projectId = p.id;
        pr.fromEnv = from;
        pr.toEnv = to;
        pr.imageTag = tag;
        pr.kind = kind;
        pr.requestedBy = actor;
        pr.status = Promotion.PENDING;
        pr = promotions.save(pr);
        audit.record(actor, kind.toLowerCase() + ".request", p.name + "/" + to, tag);
        return "prod".equals(to) ? pr : apply(p, pr, actor);
    }

    public Promotion approve(Project p, Promotion pr, String actor) {
        requirePending(pr);
        if (!canApprove(actor, pr)) throw new SecurityException("not allowed to approve this request");
        return apply(p, pr, actor);
    }

    public Promotion reject(Promotion pr, String actor, String reason) {
        requirePending(pr);
        if (!canApprove(actor, pr)) throw new SecurityException("not allowed to reject this request");
        pr.status = Promotion.REJECTED;
        pr.decidedBy = actor;
        pr.decidedAt = Instant.now();
        pr.message = reason;
        audit.record(actor, "promotion.reject", String.valueOf(pr.id), reason);
        return promotions.save(pr);
    }

    public boolean isApprover(String login) {
        var approvers = props.security().approvers();
        return approvers.isEmpty() || approvers.contains(login.toLowerCase());
    }

    private boolean canApprove(String actor, Promotion pr) {
        var approvers = props.security().approvers();
        if (approvers.isEmpty()) return !actor.equalsIgnoreCase(pr.requestedBy); // four-eyes by default
        return approvers.contains(actor.toLowerCase());
    }

    private Promotion apply(Project p, Promotion pr, String actor) {
        pr.decidedBy = actor;
        pr.decidedAt = Instant.now();
        try {
            gitops.setTag(p, pr.toEnv, pr.imageTag,
                    "platform: " + pr.kind.toLowerCase() + " " + p.name + " " + pr.toEnv + " -> " + pr.imageTag
                            + " (by " + actor + ")");
            if ("prod".equals(pr.toEnv)) {
                String app = ArgoCdService.appName(p, pr.toEnv);
                argo.getApp(app, true); // make Argo fetch the new commit before syncing
                argo.sync(app);
            }
            pr.status = Promotion.DONE;
            audit.record(actor, pr.kind.toLowerCase() + ".apply", p.name + "/" + pr.toEnv, pr.imageTag);
        } catch (Exception e) {
            pr.status = Promotion.FAILED;
            pr.message = e.getMessage();
            audit.record(actor, pr.kind.toLowerCase() + ".failed", p.name + "/" + pr.toEnv, e.getMessage());
        }
        return promotions.save(pr);
    }

    private static void requirePending(Promotion pr) {
        if (!Promotion.PENDING.equals(pr.status)) throw new IllegalArgumentException("request is " + pr.status);
    }
}
