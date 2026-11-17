package com.aio.platform.service;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.CiRunRepo;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    public static final Set<String> SEVERITIES = Set.of("HIGH,CRITICAL", "CRITICAL", "NONE");

    private final CiRunRepo runs;
    private final TektonService tekton;
    private final AuditService audit;
    private final PromotionService approvals;
    private final boolean independentDelivery;
    private final jakarta.persistence.EntityManager entities;
    private final DeliveryConfigurationService deliveryConfiguration;
    private final RegistryConnections registryConnections;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public RunService(CiRunRepo runs, TektonService tekton, AuditService audit, PromotionService approvals,
                      jakarta.persistence.EntityManager entities, DeliveryConfigurationService deliveryConfiguration,
                      RegistryConnections registryConnections, org.springframework.transaction.support.TransactionTemplate transactions,
                      @org.springframework.beans.factory.annotation.Value("${app.delivery.independent:false}") boolean independentDelivery) {
        this.runs = runs;
        this.tekton = tekton;
        this.audit = audit;
        this.approvals = approvals;
        this.entities = entities;
        this.deliveryConfiguration = deliveryConfiguration;
        this.registryConnections = registryConnections;
        this.transactions = transactions;
        this.independentDelivery = independentDelivery;
    }

    /**
     * @param gitRef  branch name or commit SHA to build
     * @param deploy  true = bump dev after a green build; false = build + scan only (PRs)
     */
    public CiRun trigger(Project p, String gitRef, String trigger, String actor, boolean deploy) {
        return start(p, gitRef, trigger, actor, deploy, null, null);
    }

    /**
     * Re-runs a previous run with the same ref and deploy flag. With {@code acceptRiskReason}, an approver
     * skips the vulnerability gate for this one run (findings are still scanned and recorded).
     */
    public CiRun rerun(Project p, CiRun previous, String actor, String acceptRiskReason) {
        if (previous.isActive()) throw new IllegalArgumentException("run #" + previous.id + " is still running");
        String ref = previous.commitSha != null ? previous.commitSha : previous.gitRef;
        if (acceptRiskReason != null) {
            if (acceptRiskReason.isBlank()) throw new IllegalArgumentException("a reason is required to accept risk");
            if (!approvals.isApprover(actor)) throw new SecurityException("only approvers can accept risk");
        }
        CiRun run = start(p, ref, "RERUN", actor, previous.deploy, previous.id, acceptRiskReason);
        if (acceptRiskReason != null) {
            audit.record(actor, "run.accept_risk", p.name + "#" + run.id, "rerun of #" + previous.id + ": " + acceptRiskReason);
        }
        return run;
    }

    private CiRun start(Project p, String gitRef, String trigger, String actor, boolean deploy, Long rerunOf,
                        String acceptRiskReason) {
        String ref = gitRef == null || gitRef.isBlank() ? p.defaultBranch : gitRef.trim();
        if (!ref.matches("[A-Za-z0-9_./-]{1,200}")) throw new IllegalArgumentException("invalid git ref");
        boolean independent = independentDelivery || p.deliveryConfigHash != null;
        if (independent && runs.findByProjectIdAndStatusIn(p.id, CiRun.ACTIVE).stream()
                .anyMatch(active -> !"INDEPENDENT".equals(active.deliveryMode)))
            throw new IllegalArgumentException("Wait for legacy builds to finish before switching this project to independent delivery");

        CiRun run = new CiRun();
        run.projectId = p.id;
        run.gitRef = ref;
        run.trigger = trigger;
        run.triggeredBy = actor;
        run.deploy = deploy;
        run.rerunOf = rerunOf;
        run.gateOverridden = acceptRiskReason != null;
        run.overrideReason = acceptRiskReason;
        run.scanSeverity = run.gateOverridden ? "NONE" : severityOf(p);
        ProjectDeliveryConfig repositoryConfig = null;
        if (p.deliveryConfigHash != null) {
            var prepared = deliveryConfiguration.prepare(p, ref, trigger, deploy);
            repositoryConfig = prepared.config();
            run.gitRef = prepared.commit();
            run.deliveryConfigHash = repositoryConfig.hash();
            run.externalRegistry = "external".equals(repositoryConfig.mode());
        }
        if (independent) {
            run.deliveryMode = "INDEPENDENT";
            run.releaseStatus = deploy ? "WAITING_BUILD" : "NOT_REQUESTED";
            run.releaseImageRepo = p.imageRepo;
            run.releaseOwnerSubject = p.ownerSubject;
            if (repositoryConfig != null && "external".equals(repositoryConfig.mode()))
                run.releaseImageRepo = repositoryConfig.host() + "/" + repositoryConfig.repository();
        }
        if (independent) {
            CiRun intent = run;
            run = transactions.execute(tx -> {
                Project locked = entities.find(Project.class, p.id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                if (locked == null) throw new java.util.NoSuchElementException();
                if (!java.util.Objects.equals(locked.ownerSubject, p.ownerSubject)
                        || !java.util.Objects.equals(locked.deliveryConfigHash, p.deliveryConfigHash)
                        || !java.util.Objects.equals(locked.imageRepo, p.imageRepo))
                    throw new IllegalArgumentException("Project configuration changed; reload before starting a build");
                // Persist ordering under the release worker's lock, then commit before submitting externally.
                if (runs.findByProjectIdAndStatusIn(p.id, CiRun.ACTIVE).stream()
                        .anyMatch(active -> !"INDEPENDENT".equals(active.deliveryMode)))
                    throw new IllegalArgumentException("Legacy builds are still active");
                return runs.saveAndFlush(intent);
            });
        } else run = runs.save(run);
        boolean submitting = false;
        try {
            if (repositoryConfig != null && "external".equals(repositoryConfig.mode())) {
                registryConnections.copyForRun(p, repositoryConfig.pushConnection(), repositoryConfig.host(), repositoryConfig.repository(), "push", run.id);
                registryConnections.copyForRun(p, repositoryConfig.pullConnection(), repositoryConfig.host(), repositoryConfig.repository(), "pull", run.id);
            }
            submitting = true;
            run.pipelineRunName = tekton.createPipelineRun(p, run);
        } catch (Exception e) {
            log.warn("PipelineRun creation failed for {} run {} ({})", p.name, run.id, e.getClass().getSimpleName());
            run.status = CiRun.FAILED;
            if ("INDEPENDENT".equals(run.deliveryMode) && deploy) run.releaseStatus = "BLOCKED";
            run.message = run.externalRegistry && !submitting ? "Registry credential provisioning failed; check connection access"
                    : "Could not create PipelineRun: " + TektonService.describe(e);
            run.finishedAt = Instant.now();
            if (independent && submitting && !(e instanceof IllegalArgumentException) && !(e instanceof SecurityException) && TektonService.isRetryable(e)) {
                // An ambiguous API response must not turn a real build into an untracked failure.
                run.status = CiRun.PENDING;
                run.finishedAt = null;
                run.releaseStatus = deploy ? "WAITING_BUILD" : "NOT_REQUESTED";
                run.message = "Awaiting confirmation of build submission";
            }
        }
        run = runs.save(run);
        audit.record(actor, "run.trigger", p.name + "#" + run.id, trigger + " " + ref);
        return run;
    }

    static String severityOf(Project p) {
        return p.scanSeverity == null || !SEVERITIES.contains(p.scanSeverity) ? "HIGH,CRITICAL" : p.scanSeverity;
    }

    public void cancel(CiRun run, String actor) {
        if (!run.isActive() || run.pipelineRunName == null) throw new IllegalArgumentException("run is not active");
        tekton.cancel(run.pipelineRunName);
        audit.record(actor, "run.cancel", String.valueOf(run.id), run.pipelineRunName);
    }

    public CiRun retryRelease(CiRun run, Project project, String actor) {
        if (!"BLOCKED".equals(run.releaseStatus) || !"INDEPENDENT".equals(run.deliveryMode)
                || !CiRun.SUCCEEDED.equals(run.status) || !run.deploy)
            throw new IllegalArgumentException("Only a blocked release from a successful independent build can be retried");
        String invalid = BuildReleaseWorker.invalidResult(run, project);
        if (invalid != null) throw new IllegalArgumentException(invalid);
        run.releaseStatus = "READY";
        run.releaseAttempts = 0;
        run.releaseMessage = null;
        run.releaseNextAttempt = null;
        audit.record(actor, "release.retry", String.valueOf(run.id), project.name + "/dev");
        return runs.save(run);
    }
}
