package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.security.AioAuthorization;
import com.aio.platform.domain.Deployment;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.PromotionRepo;
import com.aio.platform.saga.SagaEngine;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * dev -> staging is applied immediately (auto-sync). Anything that touches prod
 * (promotion or rollback) becomes a PENDING request that an approver must approve.
 * Applying is delegated to the {@link PromotionSaga} (commit, sync, verify healthy, auto-revert on failure);
 * the request is APPLYING until the saga finishes and sets DONE or FAILED.
 */
@Service
public class PromotionService {
    private static final Set<String> VALID = Set.of("dev>staging", "staging>prod");

    private final PromotionRepo promotions;
    private final GitOpsService gitops;
    private final SagaEngine sagas;
    private final AuditService audit;
    private final AppProperties props;

    public PromotionService(PromotionRepo promotions, GitOpsService gitops, SagaEngine sagas,
                            AuditService audit, AppProperties props) {
        this.promotions = promotions;
        this.gitops = gitops;
        this.sagas = sagas;
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
        if (tag.startsWith("sha256:") && !ArtifactTrustPolicy.canDeploy(ArtifactTrustPolicy.Tier.NORMAL, to, true))
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
        if ("prod".equals(to)) return pr;
        if (promotions.claimPending(pr.id, actor, Instant.now()) == 0) throw new IllegalStateException("request changed concurrently");
        return startSaga(p, promotions.findById(pr.id).orElseThrow(), actor);
    }

    public Promotion approve(Project p, Promotion pr, String actor) {
        requirePending(pr);
        if (!canApprove(actor, pr)) throw new SecurityException("not allowed to approve this request");
        // atomic PENDING -> APPLYING: of two concurrent approvals (or a double click) only one proceeds
        if (promotions.claimPending(pr.id, actor, Instant.now()) == 0)
            throw new IllegalArgumentException("request is no longer pending");
        if (actor.equalsIgnoreCase(pr.requestedBy)) {
            audit.record(actor, "promotion.self_approve", p.name + "/" + pr.toEnv, pr.imageTag);
        }
        audit.record(actor, "promotion.approve", p.name + "/" + pr.toEnv, pr.imageTag);
        return startSaga(p, promotions.findById(pr.id).orElseThrow(), actor);
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

    /** Hands the claimed (APPLYING) request to the promotion saga; idempotent per promotion id. */
    private Promotion startSaga(Project p, Promotion pr, String actor) {
        var saga = sagas.start(PromotionSaga.TYPE, PromotionSaga.key(pr), PromotionSaga.data(p, pr, actor));
        pr.sagaId = saga.id;
        return promotions.save(pr);
    }

    private static void requirePending(Promotion pr) {
        if (!Promotion.PENDING.equals(pr.status)) throw new IllegalArgumentException("request is " + pr.status);
    }
}
