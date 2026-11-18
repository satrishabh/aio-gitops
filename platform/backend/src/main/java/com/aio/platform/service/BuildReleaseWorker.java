package com.aio.platform.service;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.CiRunRepo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable run rows are the release queue. Project row locks serialize environment writes across workers. */
@Component
public class BuildReleaseWorker {
    @org.springframework.beans.factory.annotation.Value("${app.runtime.workers-enabled:true}")
    boolean workersEnabled = true;

    private final CiRunRepo runs;
    private final EntityManager entities;
    private final TransactionTemplate transactions;
    private final GitOpsService gitops;

    public BuildReleaseWorker(CiRunRepo runs, EntityManager entities, TransactionTemplate transactions,
                              GitOpsService gitops) {
        this.runs = runs; this.entities = entities; this.transactions = transactions; this.gitops = gitops;
    }

    @Scheduled(fixedDelayString = "${app.delivery.poll-ms:5000}")
    public void poll() {
        if (!workersEnabled) return;
        for (Long id : runs.dueReleases(Instant.now(), PageRequest.of(0, 20))) {
            try { transactions.executeWithoutResult(tx -> apply(id)); }
            catch (RuntimeException ignored) {
                // A transaction/lock failure rolls back the claim; the durable row remains due.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Release transaction {} failed; remains queued", id);
            }
        }
    }

    void apply(Long id) {
        CiRun candidate = entities.find(CiRun.class, id);
        if (candidate == null) return;
        Project project = entities.find(Project.class, candidate.projectId, LockModeType.PESSIMISTIC_WRITE);
        if (project == null) return;
        // Refresh after taking the project lock: another worker may have completed this run.
        entities.refresh(candidate);
        if (!eligible(candidate, Instant.now())) return;
        var latest = runs.findFirstByProjectIdAndDeliveryModeAndDeployTrueOrderByIdDesc(project.id, "INDEPENDENT");
        if (latest.isEmpty() || !id.equals(latest.get().id)) {
            candidate.releaseStatus = "SUPERSEDED";
            return;
        }
        String invalid = invalidResult(candidate, project);
        if (invalid != null) {
            candidate.releaseStatus = "BLOCKED";
            candidate.releaseMessage = invalid;
            return;
        }
        candidate.releaseAttempts++;
        try {
            // setTag compares existing desired state: recovery after a lost response does not create another commit.
            gitops.setTag(project, "dev", candidate.imageDigest, "platform: release run #" + id + " " + project.name + " dev");
            candidate.releaseStatus = "COMMITTED";
            candidate.releaseMessage = "Desired image committed to GitOps; rollout is observed separately";
            candidate.releaseNextAttempt = null;
        } catch (RuntimeException e) {
            candidate.releaseStatus = candidate.releaseAttempts >= 8 ? "BLOCKED" : "RETRY_WAIT";
            candidate.releaseMessage = "GitOps write could not be confirmed; reconcile destination before manual retry";
            candidate.releaseNextAttempt = Instant.now().plusSeconds(Math.min(300, 5L << Math.min(6, candidate.releaseAttempts)));
        }
    }

    static boolean eligible(CiRun run, Instant now) {
        return "INDEPENDENT".equals(run.deliveryMode) && run.deploy && CiRun.SUCCEEDED.equals(run.status)
                && java.util.Set.of("READY", "RETRY_WAIT").contains(Objects.toString(run.releaseStatus, ""))
                && (run.releaseNextAttempt == null || !run.releaseNextAttempt.isAfter(now));
    }

    static String invalidResult(CiRun run, Project project) {
        if (run.releaseOwnerSubject == null || !run.releaseOwnerSubject.equals(project.ownerSubject))
            return "Project ownership changed or is not bound";
        if (!Objects.equals(run.releaseImageRepo, project.imageRepo)) return "Image destination changed";
        if (run.commitSha == null || !run.commitSha.matches("[a-f0-9]{40}")) return "Missing full source commit";
        if (run.imageDigest == null || !run.imageDigest.matches("sha256:[a-f0-9]{64}")) return "Missing immutable image digest";
        if (run.imageUrl == null || !run.imageUrl.startsWith(project.imageRepo + ":")) return "Unexpected image destination";
        if (run.gateOverridden || "NONE".equals(run.scanSeverity)) return "Automatic release requires an enforced vulnerability gate";
        if (run.scanSeverity == null || !java.util.Set.of("HIGH,CRITICAL", "CRITICAL").contains(run.scanSeverity))
            return "Unknown vulnerability policy";
        if (run.vulnCritical == null || run.vulnHigh == null || run.vulnCritical < 0 || run.vulnHigh < 0)
            return "Missing scan evidence";
        if (run.vulnCritical > 0 || ("HIGH,CRITICAL".equals(run.scanSeverity) && run.vulnHigh > 0))
            return "Vulnerability policy did not pass";
        return null;
    }
}
