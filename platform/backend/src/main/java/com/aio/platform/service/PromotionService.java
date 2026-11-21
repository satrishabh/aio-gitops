package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.security.AioAuthorization;
import com.aio.platform.domain.Deployment;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.PromotionRepo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * dev -> staging is applied immediately (auto-sync). Anything that touches prod
 * (promotion or rollback) becomes a PENDING request that an approver must approve,
 * after which the tag is committed and the prod Application is synced.
 *
 * <p>A committed promotion is DEPLOYING until {@link PromotionWatcher} sees the target healthy with the new image
 * (DONE) or failing (FAILED, with the pod's reason and last log lines). Before promoting, {@link #check} compares the
 * two values files and the target namespace; with findings the caller must copy the settings or confirm.
 */
@Service
public class PromotionService {
    private static final Set<String> VALID = Set.of("dev>staging", "staging>prod");

    private final PromotionRepo promotions;
    private final GitOpsService gitops;
    private final ArgoCdService argo;
    private final AuditService audit;
    private final AppProperties props;
    private final NamespaceObjects objects;
    private final ClusterRegistry clusters;

    public PromotionService(PromotionRepo promotions, GitOpsService gitops, ArgoCdService argo,
                            AuditService audit, AppProperties props, NamespaceObjects objects, ClusterRegistry clusters) {
        this.promotions = promotions;
        this.gitops = gitops;
        this.argo = argo;
        this.audit = audit;
        this.props = props;
        this.objects = objects;
        this.clusters = clusters;
    }

    /**
     * Result of the pre-promotion check. {@code clusterChecked}: whether Secret/ConfigMap existence could be verified
     * (only for envs on the hub cluster; otherwise {@code clusterNote} says why not).
     */
    public record CheckResult(boolean ok, String from, String to, String namespace, String cluster, boolean clusterChecked,
                              String clusterNote, List<PromotionCheck.Finding> missing,
                              List<PromotionCheck.Difference> differs, List<String> neverCopied) {}

    public CheckResult check(Project p, String from, String to) {
        requireValid(from, to);
        String source = gitops.readRaw(p, from).yaml();
        String target = gitops.readRaw(p, to).yaml();
        var diff = PromotionCheck.compare(source, target, from, to);
        String ns = ClusterService.namespace(p, to);
        String cluster = clusters.clusterFor(to).name();
        List<PromotionCheck.Finding> missing = new ArrayList<>(diff.missing());
        boolean checked = objects.canCheck(to);
        if (checked) {
            // what the target references now plus what it will reference once the settings are copied
            Set<PromotionCheck.ObjectRef> refs = new LinkedHashSet<>(diff.sourceRefs());
            refs.addAll(diff.targetRefs());
            missing.addAll(PromotionCheck.missingObjects(refs, r -> objects.exists(to, ns, r), from, to, ns,
                    ClusterService.namespace(p, from)));
        }
        String note = checked ? null : "Secrets and ConfigMaps in " + ns + " on cluster " + cluster
                + " can't be checked from here (the platform reaches that cluster only through Argo CD)";
        return new CheckResult(missing.isEmpty() && diff.differs().isEmpty(), from, to, ns, cluster, checked, note,
                missing, diff.differs(), PromotionCheck.NEVER_COPIED);
    }

    /** Findings left after the chosen action: Copy settings fixes everything except objects missing in the cluster. */
    static int blocking(CheckResult c, boolean copySettings) {
        int notCopyable = (int) c.missing().stream().filter(f -> !f.copyable()).count();
        return copySettings ? notCopyable : c.missing().size() + c.differs().size();
    }

    public Promotion promote(Project p, String from, String to, String actor) {
        return promote(p, from, to, actor, false, null, null);
    }

    public Promotion promote(Project p, String from, String to, String actor, boolean copySettings, String confirm) {
        return promote(p, from, to, actor, copySettings, confirm, null);
    }

    /**
     * @param copySettings copy the source's runtime settings into the target values with the tag ({@link PromotionCheck#merge})
     * @param confirm      the project name, typed by the user to promote despite remaining check findings
     * @param envOverrides values for env vars Copy settings adds (edited in the dialog); plain values only
     */
    public Promotion promote(Project p, String from, String to, String actor, boolean copySettings, String confirm,
                             Map<String, String> envOverrides) {
        requireValid(from, to);
        Map<String, String> overrides = envOverrides == null ? Map.of() : envOverrides;
        if (!overrides.isEmpty() && !copySettings) throw new IllegalArgumentException("env values can only be set with Copy settings");
        String tag = gitops.readTag(p, from);
        if (tag == null || "0.0.0".equals(tag)) throw new IllegalArgumentException(from + " has no deployed image yet");
        CheckResult c = check(p, from, to);
        // refuse bad overrides now rather than at apply/approval time
        if (!overrides.isEmpty()) PromotionCheck.merge(gitops.readRaw(p, from).yaml(), gitops.readRaw(p, to).yaml(), overrides);
        int blocking = blocking(c, copySettings);
        if (blocking > 0) {
            if (confirm == null || !confirm.trim().equals(p.name))
                throw new IllegalArgumentException("The pre-promotion check found " + blocking + " problem" + (blocking == 1 ? "" : "s")
                        + " in " + to + (copySettings ? " that Copy settings can't fix" : "")
                        + ". Fix them, or type the project name to promote anyway.");
            audit.record(actor, "promotion.force", p.name + "/" + to, blocking + " check finding(s): " + summary(c, copySettings));
        }
        return request(p, from, to, tag, "PROMOTE", actor, copySettings, overrides);
    }

    private static String summary(CheckResult c, boolean copySettings) {
        List<String> parts = new ArrayList<>();
        c.missing().stream().filter(f -> !copySettings || !f.copyable()).limit(10).forEach(f -> parts.add(f.kind() + " " + f.name()));
        if (!copySettings) c.differs().stream().limit(5).forEach(d -> parts.add(d.key()));
        return String.join(", ", parts);
    }

    private static void requireValid(String from, String to) {
        if (!VALID.contains(from + ">" + to)) throw new IllegalArgumentException("allowed: dev->staging, staging->prod");
    }

    public Promotion rollback(Project p, String env, Deployment target, String actor) {
        if (!GitOpsService.ENVS.contains(env)) throw new IllegalArgumentException("unknown env");
        if (target.imageTag == null) throw new IllegalArgumentException("image tag unknown for that deployment");
        return request(p, null, env, target.imageTag, "ROLLBACK", actor, false, Map.of());
    }

    private Promotion request(Project p, String from, String to, String tag, String kind, String actor, boolean copySettings,
                              Map<String, String> envOverrides) {
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
        pr.copySettings = copySettings ? Boolean.TRUE : null;
        pr.envOverrides = envOverrides.isEmpty() ? null : writeOverrides(envOverrides);
        pr = promotions.save(pr);
        audit.record(actor, kind.toLowerCase() + ".request", p.name + "/" + to, tag);
        return "prod".equals(to) ? pr : apply(p, pr, actor);
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
            String message = "platform: " + pr.kind.toLowerCase() + " " + p.name + " " + pr.toEnv + " -> " + pr.imageTag;
            boolean copy = Boolean.TRUE.equals(pr.copySettings) && pr.fromEnv != null;
            if (copy) {
                String source = gitops.readRaw(p, pr.fromEnv).yaml();
                Map<String, String> overrides = readOverrides(pr.envOverrides);
                gitops.setTagWithSettings(p, pr.toEnv, pr.imageTag, target -> PromotionCheck.merge(source, target, overrides),
                        message + " with settings from " + pr.fromEnv + " (by " + actor + ")");
            } else {
                gitops.setTag(p, pr.toEnv, pr.imageTag, message + " (by " + actor + ")");
            }
            String app = ArgoCdService.appName(p, pr.toEnv);
            if ("prod".equals(pr.toEnv)) {
                argo.getApp(app, true); // make Argo fetch the new commit before syncing
                argo.sync(app);
            } else {
                refreshQuietly(app);    // auto-sync envs: pick the commit up now rather than at Argo's next git poll
            }
            // Not DONE yet: PromotionWatcher decides once the target runs the new image healthy (or doesn't)
            pr.status = Promotion.DEPLOYING;
            pr.deployingSince = Instant.now();
            audit.record(actor, pr.kind.toLowerCase() + ".apply", p.name + "/" + pr.toEnv,
                    pr.imageTag + (copy ? " (settings copied from " + pr.fromEnv + ")" : ""));
        } catch (Exception e) {
            pr.status = Promotion.FAILED;
            pr.message = e.getMessage();
            audit.record(actor, pr.kind.toLowerCase() + ".failed", p.name + "/" + pr.toEnv, e.getMessage());
        }
        return promotions.save(pr);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    static String writeOverrides(Map<String, String> overrides) {
        return JSON.writeValueAsString(new java.util.TreeMap<>(overrides));
    }

    static Map<String, String> readOverrides(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return JSON.readValue(json, new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
    }

    private void refreshQuietly(String app) {
        try {
            argo.getApp(app, true);
        } catch (RuntimeException e) {
            // best effort: Argo CD polls git on its own
        }
    }

    private static void requirePending(Promotion pr) {
        if (!Promotion.PENDING.equals(pr.status)) throw new IllegalArgumentException("request is " + pr.status);
    }
}
