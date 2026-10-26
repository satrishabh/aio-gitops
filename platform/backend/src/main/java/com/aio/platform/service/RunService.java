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

    public RunService(CiRunRepo runs, TektonService tekton, AuditService audit, PromotionService approvals) {
        this.runs = runs;
        this.tekton = tekton;
        this.audit = audit;
        this.approvals = approvals;
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
        run = runs.save(run);
        try {
            run.pipelineRunName = tekton.createPipelineRun(p, run);
        } catch (Exception e) {
            log.warn("PipelineRun creation failed for {} run {}", p.name, run.id, e);
            run.status = CiRun.FAILED;
            run.message = "Could not create PipelineRun: " + TektonService.describe(e);
            run.finishedAt = Instant.now();
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
}
