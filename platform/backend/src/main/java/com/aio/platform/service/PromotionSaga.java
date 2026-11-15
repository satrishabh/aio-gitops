package com.aio.platform.service;

import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import com.aio.platform.saga.Saga;
import com.aio.platform.saga.Saga.Context;
import com.aio.platform.saga.Saga.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Promotion / rollback of an image tag into an environment, as an orchestrated saga.
 *
 * <pre>
 * capture-current-tag → commit-tag (↺ restore previous tag) → sync → verify-healthy → smoke-test
 * </pre>
 *
 * Fixes two problems of the old synchronous apply: (1) a failure after the Git commit left the promotion FAILED
 * while the new tag stayed in GitOps; now the commit is compensated (previous tag restored and, for prod, synced);
 * (2) DONE meant "commit pushed"; now it means Argo CD reports the app Synced, Healthy and running the new image.
 *
 * <p>Verification is two-stage, like Argo Rollouts analysis / Harness continuous verification: Argo CD must report
 * the new image Synced and Healthy, then the app's public URL must answer a few HTTP samples without a 5xx.
 * A verification failure follows the environment's failure strategy: automatic rollback (default), or a pause for
 * an approver to retry, skip or roll back ({@code AIO_PROMOTION_MANUAL_ENVS}).
 *
 * <p>Data: promotionId, projectId, toEnv, tag, kind, actor; previousTag is captured by the first step.
 */
@Component
public class PromotionSaga implements Saga.Definition {
    public static final String TYPE = "promotion";
    static final Duration RECHECK = Duration.ofSeconds(20);
    /** Degraded for longer than this counts as a failed rollout (pods crash-looping, failed probes). */
    static final Duration DEGRADED_GRACE = Duration.ofMinutes(5);

    private final ProjectRepo projects;
    private final PromotionRepo promotions;
    private final GitOpsService gitops;
    private final ArgoCdService argo;
    private final AuditService audit;
    private final SmokeProbe probe;
    private final Policy policy;

    /**
     * How promotions are verified and what happens when verification fails.
     *
     * @param manualEnvs environments whose failures pause for a decision instead of rolling back at once
     * @param smokeSamples successful HTTP samples needed; {@code smokeFailureLimit} failed samples fail the step
     */
    public record Policy(boolean verifyHealth, Duration timeout, Set<String> manualEnvs, Duration interventionTimeout,
                         boolean smoke, String smokePath, int smokeSamples, int smokeFailureLimit, Duration smokeInterval) {
        public static Policy defaults(boolean verifyHealth, Duration timeout) {
            return new Policy(verifyHealth, timeout, Set.of(), Duration.ofHours(2), false, "/", 4, 2, Duration.ofSeconds(15));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PromotionSaga(ProjectRepo projects, PromotionRepo promotions, GitOpsService gitops, ArgoCdService argo,
                         AuditService audit, SmokeProbe probe,
                         @Value("${app.promotion.verify-health:true}") boolean verify,
                         @Value("${app.promotion.timeout:PT20M}") Duration timeout,
                         @Value("${app.promotion.manual-intervention-envs:}") String manualEnvs,
                         @Value("${app.promotion.intervention-timeout:PT2H}") Duration interventionTimeout,
                         @Value("${app.promotion.smoke.enabled:true}") boolean smoke,
                         @Value("${app.promotion.smoke.path:/}") String smokePath,
                         @Value("${app.promotion.smoke.samples:4}") int smokeSamples,
                         @Value("${app.promotion.smoke.failure-limit:2}") int smokeFailureLimit,
                         @Value("${app.promotion.smoke.interval:PT15S}") Duration smokeInterval) {
        this(projects, promotions, gitops, argo, audit, probe, new Policy(verify, timeout, envs(manualEnvs), interventionTimeout,
                smoke, smokePath, Math.max(1, smokeSamples), Math.max(1, smokeFailureLimit), smokeInterval));
    }

    public PromotionSaga(ProjectRepo projects, PromotionRepo promotions, GitOpsService gitops, ArgoCdService argo,
                         AuditService audit, SmokeProbe probe, Policy policy) {
        this.projects = projects;
        this.promotions = promotions;
        this.gitops = gitops;
        this.argo = argo;
        this.audit = audit;
        this.probe = probe;
        this.policy = policy;
    }

    /** Health verification only, rollback on failure (unit tests). */
    public PromotionSaga(ProjectRepo projects, PromotionRepo promotions, GitOpsService gitops, ArgoCdService argo,
                         AuditService audit, boolean verify, Duration timeout) {
        this(projects, promotions, gitops, argo, audit, null, Policy.defaults(verify, timeout));
    }

    static Set<String> envs(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new java.util.HashSet<>();
        for (String e : csv.split(",")) if (!e.isBlank()) out.add(e.trim().toLowerCase());
        return Set.copyOf(out);
    }

    public static String key(Promotion pr) {
        return "promotion:" + pr.id;
    }

    public static Map<String, Object> data(Project p, Promotion pr, String actor) {
        return Map.of("promotionId", pr.id, "projectId", p.id, "toEnv", pr.toEnv, "tag", pr.imageTag,
                "kind", pr.kind, "actor", actor);
    }

    @Override public String type() { return TYPE; }
    @Override public Duration timeout() { return policy.timeout(); }
    @Override public int maxAttempts() { return 6; }
    @Override public Duration interventionTimeout() { return policy.interventionTimeout(); }

    /** Harness-style failure strategy per environment: pause for a decision, or roll back at once. */
    @Override
    public Saga.OnFailure onFailure(Context c) {
        return policy.manualEnvs().contains(c.str("toEnv")) ? Saga.OnFailure.MANUAL_INTERVENTION : Saga.OnFailure.ROLLBACK;
    }

    @Override
    public List<Saga.Step> steps() {
        List<Saga.Step> steps = new ArrayList<>(List.of(captureCurrent(), commitTag(), sync()));
        if (policy.verifyHealth()) steps.add(verifyHealthy());
        if (policy.smoke() && probe != null) steps.add(smokeTest());
        return steps;
    }

    // ------------------------------------------------------------------ steps

    Saga.Step captureCurrent() {
        return new Saga.Step() {
            public String name() { return "capture-current-tag"; }
            public Result execute(Context c) {
                if (!c.has("previousTag")) c.put("previousTag", gitops.readTag(project(c), c.str("toEnv")));
                return Result.done();
            }
        };
    }

    Saga.Step commitTag() {
        return new Saga.Step() {
            public String name() { return "commit-tag"; }

            /** Idempotent: setTag compares the file before writing, so a re-run after a crash is a no-op. */
            public Result execute(Context c) {
                Project p = project(c);
                gitops.setTag(p, c.str("toEnv"), c.str("tag"), "platform: " + c.str("kind").toLowerCase() + " " + p.name
                        + " " + c.str("toEnv") + " -> " + c.str("tag") + " (by " + c.str("actor") + ", saga " + c.sagaId() + ")");
                c.put("committed", true);
                return Result.done();
            }

            public void compensate(Context c) {
                String previous = c.str("previousTag");
                if (!restorable(previous) || previous.equals(c.str("tag"))) return;   // nothing deployed before: leave it
                Project p = project(c);
                String env = c.str("toEnv");
                gitops.setTag(p, env, previous, "platform: revert " + p.name + " " + env + " -> " + previous
                        + " (saga " + c.sagaId() + ": " + c.lastError() + ")");
                if ("prod".equals(env)) {
                    String app = ArgoCdService.appName(p, env);
                    argo.getApp(app, true);
                    argo.sync(app);
                }
                c.put("reverted", true);
                audit.record("platform", c.str("kind").toLowerCase() + ".revert", p.name + "/" + env, previous);
            }
        };
    }

    Saga.Step sync() {
        return new Saga.Step() {
            public String name() { return "sync"; }
            public Result execute(Context c) {
                String app = ArgoCdService.appName(project(c), c.str("toEnv"));
                argo.getApp(app, true);                         // fetch the new commit now instead of in ~3 min
                if ("prod".equals(c.str("toEnv"))) argo.sync(app); // prod is manual-sync; dev/staging auto-sync
                return Result.done();
            }
        };
    }

    Saga.Step verifyHealthy() {
        return new Saga.Step() {
            public String name() { return "verify-healthy"; }
            public Result execute(Context c) {
                Project p = project(c);
                Map<String, Object> app = argo.getApp(ArgoCdService.appName(p, c.str("toEnv")), false);
                if (app == null) return Result.retry("Argo CD application not found");
                return evaluate(app, p.imageRepo, c.str("tag"), c);
            }
        };
    }

    /**
     * Samples the app's public URL until {@code smokeSamples} answers without a 5xx, failing after
     * {@code smokeFailureLimit} bad samples (5xx, timeout, connection refused). One sample per execution, so the
     * saga sleeps between samples instead of holding a thread. Skipped when the environment has no public URL.
     */
    Saga.Step smokeTest() {
        return new Saga.Step() {
            public String name() { return "smoke-test"; }
            public Result execute(Context c) {
                if (!c.has("smokeUrl")) {
                    String base = gitops.readValuesCached(project(c), c.str("toEnv")).appUrl();
                    if (base == null || base.isBlank()) {
                        c.put("smoke", "skipped: no public URL for " + c.str("toEnv"));
                        return Result.done();
                    }
                    String path = policy.smokePath().startsWith("/") ? policy.smokePath() : "/" + policy.smokePath();
                    c.put("smokeUrl", base.replaceAll("/+$", "") + path);
                }
                return sample(c, probe.status(c.str("smokeUrl")), policy);
            }
        };
    }

    static Result sample(Context c, int code, Policy policy) {
        boolean ok = code >= 200 && code < 500;
        long good = (c.has("smokeOk") ? c.num("smokeOk") : 0) + (ok ? 1 : 0);
        long bad = (c.has("smokeFailed") ? c.num("smokeFailed") : 0) + (ok ? 0 : 1);
        c.put("smokeOk", good);
        c.put("smokeFailed", bad);
        String last = code < 0 ? "unreachable" : "HTTP " + code;
        c.put("smokeLast", last);
        if (bad >= policy.smokeFailureLimit())
            return Result.fail("smoke test failed: " + bad + " bad sample(s), last " + last + " from " + c.str("smokeUrl"));
        if (good >= policy.smokeSamples()) {
            c.put("smoke", good + "/" + (good + bad) + " samples ok");
            return Result.done();
        }
        return Result.waitUntil(c.now().plus(policy.smokeInterval()), "smoke test " + good + "/" + policy.smokeSamples() + " ok");
    }

    /** Decides from an Argo Application's status whether the rollout is done, still going, or failed. */
    static Result evaluate(Map<String, Object> app, String imageRepo, String tag, Context c) {
        Object status = app.get("status");
        String sync = J.str(status, "sync", "status");
        String health = J.str(status, "health", "status");
        boolean running = runsImage(J.get(status, "summary", "images"), imageRepo, tag);
        Instant now = c.now();
        if ("Synced".equals(sync) && "Healthy".equals(health) && running) return Result.done();
        if ("Degraded".equals(health) && running) {
            if (!c.has("degradedSince")) c.put("degradedSince", now.toString());
            if (Duration.between(Instant.parse(c.str("degradedSince")), now).compareTo(DEGRADED_GRACE) > 0)
                return Result.fail("rollout Degraded for over " + DEGRADED_GRACE.toMinutes() + " min");
        } else {
            c.put("degradedSince", null);
        }
        return Result.waitUntil(now.plus(RECHECK), "sync=" + sync + " health=" + health + (running ? "" : " (old image)"));
    }

    static boolean runsImage(Object images, String imageRepo, String tag) {
        if (!(images instanceof List<?> list) || imageRepo == null || tag == null) return false;
        String want = tag.startsWith("sha256:") ? imageRepo + "@" + tag : imageRepo + ":" + tag;
        return list.stream().map(String::valueOf).anyMatch(want::equals);
    }

    static boolean restorable(String tag) {
        return tag != null && !tag.isBlank() && !"0.0.0".equals(tag);
    }

    // ------------------------------------------------------------------ outcome

    @Override
    public void onPaused(Context c, String reason) {
        promotions.findById(c.num("promotionId")).ifPresent(pr -> {
            pr.status = Promotion.PAUSED;
            pr.message = "needs a decision (retry, skip or roll back; rolls back by itself after "
                    + policy.interventionTimeout().toMinutes() + " min): " + reason;
            promotions.save(pr);
            audit.record("platform", c.str("kind").toLowerCase() + ".paused", target(c), reason);
        });
    }

    @Override
    public void onResumed(Context c, String action, String actor) {
        promotions.findById(c.num("promotionId")).ifPresent(pr -> {
            pr.status = Promotion.APPLYING;
            pr.message = action.toLowerCase() + " by " + actor;
            promotions.save(pr);
        });
    }

    private String target(Context c) {
        return projects.findById(c.num("projectId")).map(p -> p.name).orElse("?") + "/" + c.str("toEnv");
    }

    @Override
    public void onFinished(Context c, String status) {
        Promotion pr = promotions.findById(c.num("promotionId")).orElse(null);
        if (pr == null) return;
        pr.previousTag = c.str("previousTag");
        String target = projects.findById(c.num("projectId")).map(p -> p.name).orElse("?") + "/" + c.str("toEnv");
        String kind = c.str("kind").toLowerCase();
        switch (status) {
            case "SUCCEEDED" -> {
                pr.status = Promotion.DONE;
                pr.message = null;
                audit.record(c.str("actor"), kind + ".apply", target, c.str("tag"));
            }
            case "COMPENSATED" -> {
                pr.status = Promotion.FAILED;
                pr.message = (c.has("reverted") ? "rolled back to " + pr.previousTag + ": " : "") + c.lastError();
                audit.record("platform", kind + ".failed", target, pr.message);
            }
            default -> {
                pr.status = Promotion.FAILED;
                pr.message = "needs manual attention: " + c.lastError();
                audit.record("platform", kind + ".stuck", target, pr.message);
            }
        }
        promotions.save(pr);
    }

    private Project project(Context c) {
        return projects.findById(c.num("projectId"))
                .orElseThrow(() -> new IllegalStateException("project " + c.num("projectId") + " no longer exists"));
    }
}
