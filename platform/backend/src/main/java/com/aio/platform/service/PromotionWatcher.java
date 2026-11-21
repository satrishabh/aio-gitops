package com.aio.platform.service;

import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns DEPLOYING promotions into DONE or FAILED by watching the target env's Argo CD app:
 * <ul>
 *   <li>Synced, Healthy and running the promoted image -> DONE;</li>
 *   <li>app Degraded, or a pod running the new image stuck in CrashLoopBackOff / ImagePullBackOff /
 *       CreateContainerConfigError... -> FAILED with that reason and the container's last log lines;</li>
 *   <li>not Healthy within {@code app.promotion.health-timeout-ms} (10 minutes) -> FAILED.</li>
 * </ul>
 * Pod state and logs are read through Argo CD, which holds the credentials of every env's cluster.
 */
@Component
public class PromotionWatcher {
    private static final Logger log = LoggerFactory.getLogger(PromotionWatcher.class);
    static final int MAX_LOG_LINES = 20, MAX_LOG_BYTES = 2048;
    /** Container waiting reasons that won't fix themselves. */
    static final Set<String> FATAL = Set.of("CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "CreateContainerConfigError",
            "CreateContainerError", "InvalidImageName", "RunContainerError", "ErrImageNeverPull");
    /** A pod reason counts only after this long, so a first ErrImagePull retry doesn't fail the promotion. */
    static final Duration POD_GRACE = Duration.ofSeconds(60);
    static final Duration DEGRADED_TRUST = Duration.ofMinutes(3);

    @Value("${app.runtime.workers-enabled:true}")
    boolean workersEnabled = true;
    @Value("${app.promotion.health-timeout-ms:600000}")
    long timeoutMs = 600_000;

    private final PromotionRepo promotions;
    private final ProjectRepo projects;
    private final ArgoCdService argo;
    private final GitOpsService gitops;
    private final AuditService audit;

    public PromotionWatcher(PromotionRepo promotions, ProjectRepo projects, ArgoCdService argo, GitOpsService gitops,
                            AuditService audit) {
        this.promotions = promotions;
        this.projects = projects;
        this.argo = argo;
        this.gitops = gitops;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.watch.promotion-ms:15000}", initialDelayString = "${app.watch.promotion-initial-ms:20000}")
    public void poll() {
        if (!workersEnabled) return;
        for (Promotion pr : promotions.findByStatusOrderByIdDesc(Promotion.DEPLOYING)) {
            try {
                watch(pr, Instant.now());
            } catch (Exception e) {
                log.debug("promotion {} health check failed: {}", pr.id, e.getMessage());
            }
        }
    }

    /** One look at a DEPLOYING promotion; saves it when it reaches DONE or FAILED. */
    void watch(Promotion pr, Instant now) {
        Project p = projects.findById(pr.projectId).orElse(null);
        if (p == null) {
            finish(pr, Promotion.FAILED, "project was deleted while deploying", null, now);
            return;
        }
        Instant since = pr.deployingSince != null ? pr.deployingSince : pr.decidedAt != null ? pr.decidedAt : pr.requestedAt;
        Duration elapsed = Duration.between(since, now);

        // Someone changed the env's tag since (a newer promotion, a rollback, an edit on the CD page)
        GitOpsService.EnvValues desired = null;
        try {
            desired = gitops.readValuesCached(p, pr.toEnv);
        } catch (RuntimeException e) {
            log.debug("values read for promotion {} failed: {}", pr.id, e.getMessage());
        }
        if (desired != null && desired.tag() != null && !desired.tag().equals(pr.imageTag)) {
            finish(pr, Promotion.FAILED, "superseded: " + pr.toEnv + " now deploys " + desired.tag()
                    + " (changed before " + pr.imageTag + " was confirmed healthy)", null, now);
            return;
        }

        String app = ArgoCdService.appName(p, pr.toEnv);
        Map<String, Object> application = argo.getApp(app, false);
        Object status = application == null ? null : application.get("status");
        String sync = J.str(status, "sync", "status");
        String health = J.str(status, "health", "status");
        boolean scaledToZero = desired != null && desired.replicas() != null && desired.replicas() == 0;
        boolean newImage = runsImage(status, p.imageRepo, pr.imageTag);

        if ("Synced".equals(sync) && "Healthy".equals(health) && (newImage || scaledToZero)) {
            finish(pr, Promotion.DONE, scaledToZero && !newImage
                    ? pr.toEnv + " is scaled to 0: " + pr.imageTag + " starts when the env is started" : null, null, now);
            return;
        }

        boolean pastGrace = elapsed.compareTo(POD_GRACE) >= 0;
        PodFailure pod = pastGrace ? failingPod(app, pr.imageTag) : null;
        // Only a pod running the promoted image speaks for this promotion (old pods may still be crash-looping)
        if (pod != null && pod.mine() && pod.fatal()) {
            finish(pr, Promotion.FAILED, pod.reason(), pod.logs(), now);
            return;
        }
        // Degraded before Argo picked up the commit is the old version's state; trust it once the new image runs
        // or Argo has certainly polled git (3 minutes by default)
        if ("Degraded".equals(health) && pastGrace && (newImage || elapsed.compareTo(DEGRADED_TRUST) >= 0)) {
            String msg = J.str(status, "health", "message");
            finish(pr, Promotion.FAILED, pr.toEnv + " is Degraded" + (msg == null || msg.isBlank() ? "" : ": " + msg)
                    + (pod == null ? "" : " - " + pod.reason()), pod == null ? null : pod.logs(), now);
            return;
        }
        if (elapsed.toMillis() >= timeoutMs) {
            String state = "health " + (health == null ? "unknown" : health) + ", sync " + (sync == null ? "unknown" : sync)
                    + (newImage ? "" : ", " + pr.imageTag + " not running yet");
            finish(pr, Promotion.FAILED, "not healthy after " + Math.round(timeoutMs / 60000.0) + " minutes (" + state + ")"
                    + (pod == null ? "" : ": " + pod.reason()), pod == null ? null : pod.logs(), now);
        }
    }

    private void finish(Promotion pr, String status, String message, String detail, Instant now) {
        pr.status = status;
        if (message != null) pr.message = message;
        pr.detail = detail;
        promotions.save(pr);
        String target = pr.toEnv + " " + pr.imageTag;
        Project p = projects.findById(pr.projectId).orElse(null);
        audit.record("argocd", Promotion.DONE.equals(status) ? "promotion.healthy" : "promotion.unhealthy",
                (p == null ? String.valueOf(pr.projectId) : p.name) + "/" + pr.toEnv,
                Promotion.DONE.equals(status) ? target : target + ": " + message);
        log.info("promotion {} -> {}{}", pr.id, status, message == null ? "" : " (" + message + ")");
    }

    /** Argo's image summary (or, as it can lag, a pod in the tree) shows the promoted image. */
    static boolean runsImage(Object status, String imageRepo, String tag) {
        Object images = J.get(status, "summary", "images");
        if (!(images instanceof List<?> list)) return false;
        String want = imageRef(imageRepo, tag);
        return list.stream().anyMatch(i -> want.equals(String.valueOf(i)));
    }

    static String imageRef(String repo, String tag) {
        return tag.startsWith("sha256:") ? repo + "@" + tag : repo + ":" + tag;
    }

    /** reason: "CrashLoopBackOff: <message> (exit code 1)"; fatal: a reason in {@link #FATAL}; logs: bounded tail. */
    record PodFailure(String reason, boolean fatal, boolean mine, String logs) {}

    /** The first unhealthy pod, preferring pods running the promoted image, with its container state and logs. */
    PodFailure failingPod(String app, String tag) {
        Map<String, Object> tree = argo.resourceTree(app);
        if (tree == null) return null;
        Map<String, Object> pick = null;
        boolean mine = false;
        for (Map<String, Object> n : J.list(tree, "nodes")) {
            if (!"Pod".equals(n.get("kind")) || "Healthy".equals(J.str(n, "health", "status"))) continue;
            boolean runsTag = J.get(n, "images") instanceof List<?> imgs
                    && imgs.stream().anyMatch(i -> String.valueOf(i).endsWith(":" + tag) || String.valueOf(i).endsWith("@" + tag));
            if (runsTag) { pick = n; mine = true; break; }
            if (pick == null) pick = n;
        }
        if (pick == null) return null;
        String ns = J.str(pick, "namespace"), name = J.str(pick, "name");
        Map<String, Object> pod = argo.resource(app, new ArgoCdService.Ref(ns, "", "v1", "Pod", name));
        ContainerState cs = containerState(pod);
        if (cs == null) {
            String msg = J.str(pick, "health", "message");
            return msg == null ? null : new PodFailure("pod " + name + ": " + msg, false, mine, null);
        }
        String logs = null;
        if (cs.ran()) {
            try {
                logs = tail(argo.logs(app, ns, name, cs.container(), cs.restarted(), MAX_LOG_LINES));
            } catch (RuntimeException e) {
                log.debug("logs of {} failed: {}", name, e.getMessage());
            }
        }
        return new PodFailure(cs.describe(), FATAL.contains(cs.reason()), mine, logs);
    }

    /** The state of the first container that isn't running and ready. */
    record ContainerState(String container, String reason, String message, Integer exitCode, boolean restarted, boolean ran) {
        String describe() {
            String out = reason + (message == null || message.isBlank() ? "" : ": " + message);
            if (exitCode != null) out += " (exit code " + exitCode + ")";
            return out.length() > 500 ? out.substring(0, 500) + "…" : out;
        }
    }

    static ContainerState containerState(Map<String, Object> pod) {
        if (pod == null) return null;
        Object status = pod.get("status");
        for (String key : List.of("initContainerStatuses", "containerStatuses")) {
            for (Map<String, Object> c : J.list(status, key)) {
                if (Boolean.TRUE.equals(c.get("ready"))) continue;
                String name = J.str(c, "name");
                int restarts = c.get("restartCount") instanceof Number n ? n.intValue() : 0;
                Object last = J.get(c, "lastState", "terminated");
                Integer lastExit = J.get(last, "exitCode") instanceof Number n ? n.intValue() : null;
                Object waiting = J.get(c, "state", "waiting");
                if (waiting != null) {
                    String reason = J.str(waiting, "reason");
                    // never started: config / image errors; restarted: crash loop (the previous run has the logs)
                    boolean ran = restarts > 0 || last != null;
                    return new ContainerState(name, reason == null ? "Waiting" : reason, J.str(waiting, "message"), lastExit, ran && restarts > 0, ran);
                }
                Object terminated = J.get(c, "state", "terminated");
                if (terminated != null) {
                    Integer exit = J.get(terminated, "exitCode") instanceof Number n ? n.intValue() : null;
                    String reason = J.str(terminated, "reason");
                    return new ContainerState(name, reason == null ? "Terminated" : reason, J.str(terminated, "message"), exit, false, true);
                }
                if (J.get(c, "state", "running") != null && restarts > 0)
                    return new ContainerState(name, "Restarting", "running but not ready after " + restarts + " restart(s)", lastExit, true, true);
            }
        }
        return null;
    }

    /** Last {@link #MAX_LOG_LINES} lines, at most {@link #MAX_LOG_BYTES} UTF-8 bytes (older lines dropped first). */
    static String tail(String logs) {
        if (logs == null || logs.isBlank()) return null;
        List<String> lines = logs.lines().toList();
        lines = lines.subList(Math.max(0, lines.size() - MAX_LOG_LINES), lines.size());
        String out = String.join("\n", lines);
        while (out.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LOG_BYTES) {
            int nl = out.indexOf('\n');
            if (nl < 0) {
                byte[] b = out.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out = new String(b, b.length - MAX_LOG_BYTES, MAX_LOG_BYTES, java.nio.charset.StandardCharsets.UTF_8);
                // a cut multi-byte char decodes to U+FFFD; drop it
                out = out.replace("�", "");
                break;
            }
            out = out.substring(nl + 1);
        }
        return out;
    }
}
