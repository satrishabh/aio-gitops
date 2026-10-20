package com.aio.platform.service;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.CiRunRepo;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class RunService {
    private final CiRunRepo runs;
    private final TektonService tekton;
    private final AuditService audit;

    public RunService(CiRunRepo runs, TektonService tekton, AuditService audit) {
        this.runs = runs;
        this.tekton = tekton;
        this.audit = audit;
    }

    /**
     * @param gitRef  branch name or commit SHA to build
     * @param deploy  true = bump dev after a green build; false = build + scan only (PRs)
     */
    public CiRun trigger(Project p, String gitRef, String trigger, String actor, boolean deploy) {
        String ref = gitRef == null || gitRef.isBlank() ? p.defaultBranch : gitRef.trim();
        if (!ref.matches("[A-Za-z0-9_./-]{1,200}")) throw new IllegalArgumentException("invalid git ref");

        CiRun run = new CiRun();
        run.projectId = p.id;
        run.gitRef = ref;
        run.trigger = trigger;
        run.triggeredBy = actor;
        run.deploy = deploy;
        run = runs.save(run);
        try {
            run.pipelineRunName = tekton.createPipelineRun(p, run);
        } catch (Exception e) {
            run.status = CiRun.FAILED;
            run.message = "Could not create PipelineRun: " + e.getMessage();
            run.finishedAt = Instant.now();
        }
        run = runs.save(run);
        audit.record(actor, "run.trigger", p.name + "#" + run.id, trigger + " " + ref);
        return run;
    }

    public void cancel(CiRun run, String actor) {
        if (!run.isActive() || run.pipelineRunName == null) throw new IllegalArgumentException("run is not active");
        tekton.cancel(run.pipelineRunName);
        audit.record(actor, "run.cancel", String.valueOf(run.id), run.pipelineRunName);
    }
}
