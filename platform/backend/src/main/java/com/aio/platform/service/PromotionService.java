package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.security.AioAuthorization;
import com.aio.platform.domain.Deployment;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.PromotionRepo;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Promotions follow each environment's {@code promote_from} (dev -> staging -> prod -> ...). Into an environment
 * without approval they are applied immediately (auto-sync). Anything that touches an approval-required env
 * (promotion or rollback; prod and any env after it) becomes a PENDING request that an approver must approve,
 * after which the tag is committed and that env's Application is synced.
 */
@Service
public class PromotionService {
    /** The environments (was a fixed dev>staging>prod list); field-injected to keep the constructor stable. */
    @org.springframework.beans.factory.annotation.Autowired
    EnvironmentRegistry environments;

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
        if (!validPath(environments, from, to)) throw new IllegalArgumentException("allowed: " + allowedPaths(environments));
        String tag = gitops.readTag(p, from);
        if (tag == null || "0.0.0".equals(tag)) throw new IllegalArgumentException(from + " has no deployed image yet");
        return request(p, from, to, tag, "PROMOTE", actor);
    }

    public Promotion rollback(Project p, String env, Deployment target, String actor) {
        if (!environments.exists(env)) throw new IllegalArgumentException("unknown env");
        if (target.imageTag == null) throw new IllegalArgumentException("image tag unknown for that deployment");
        return request(p, null, env, target.imageTag, "ROLLBACK", actor);
    }

    private Promotion request(Project p, String from, String to, String tag, String kind, String actor) {
        if (tag.startsWith("sha256:") && !ArtifactTrustPolicy.canDeploy(ArtifactTrustPolicy.Tier.NORMAL, environments.trustClass(to), true))
            throw new IllegalArgumentException("Digest releases require verified artifact evidence for staging and golden evidence for production; evidence verification is not configured yet");
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
        return environments.approvalRequired(to) ? pr : apply(p, pr, actor);
    }

    /** A promotion may only go from an environment's {@code promote_from} into it. */
    static boolean validPath(EnvironmentRegistry envs, String from, String to) {
        return from != null && envs.exists(to) && from.equals(envs.promoteFrom(to));
    }

    static String allowedPaths(EnvironmentRegistry envs) {
        return envs.list().stream().filter(e -> e.promoteFrom() != null).map(e -> e.promoteFrom() + "->" + e.name())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public Promotion approve(Project p, Promotion pr, String actor) {
        requirePending(pr);
        if (!canApprove(actor, pr)) throw new SecurityException("not allowed to approve this request");
        if (actor.equalsIgnoreCase(pr.requestedBy)) {
            audit.record(actor, "promotion.self_approve", p.name + "/" + pr.toEnv, pr.imageTag);
        }
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
        if (AioAuthorization.currentUserHas(AioAuthorization.ADMIN, AioAuthorization.APPROVER)) return true;
        var approvers = props.security().approvers();
        return approvers.isEmpty() || approvers.contains(login.toLowerCase());
    }

    public boolean canSelfApprove(String login) {
        if (AioAuthorization.currentUserHas(AioAuthorization.ADMIN, AioAuthorization.APPROVER)) return true;
        return canApprove(props.security().approvers(), login, login);
    }

    /**
     * With APPROVERS configured, listed approvers may approve anything, including their own requests
     * (audited as a self-approval). With no APPROVERS, the four-eyes rule applies: anyone but the requester.
     */
    static boolean canApprove(java.util.List<String> approvers, String actor, String requestedBy) {
        if (approvers.isEmpty()) return !actor.equalsIgnoreCase(requestedBy);
        return approvers.contains(actor.toLowerCase());
    }

    private boolean canApprove(String actor, Promotion pr) {
        if (AioAuthorization.currentUserHas(AioAuthorization.ADMIN, AioAuthorization.APPROVER)) return true;
        return canApprove(props.security().approvers(), actor, pr.requestedBy);
    }

    private Promotion apply(Project p, Promotion pr, String actor) {
        pr.decidedBy = actor;
        pr.decidedAt = Instant.now();
        try {
            gitops.setTag(p, pr.toEnv, pr.imageTag,
                    "platform: " + pr.kind.toLowerCase() + " " + p.name + " " + pr.toEnv + " -> " + pr.imageTag
                            + " (by " + actor + ")");
            if (environments.approvalRequired(pr.toEnv)) {   // no auto-sync there
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
