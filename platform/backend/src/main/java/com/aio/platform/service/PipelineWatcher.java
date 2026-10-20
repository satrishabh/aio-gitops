package com.aio.platform.service;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.RunStep;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.RunStepRepo;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls active PipelineRuns and mirrors their status, steps and results into the database. */
@Component
public class PipelineWatcher {
    private static final Logger log = LoggerFactory.getLogger(PipelineWatcher.class);

    private final CiRunRepo runs;
    private final RunStepRepo steps;
    private final TektonService tekton;
    private final AuditService audit;

    public PipelineWatcher(CiRunRepo runs, RunStepRepo steps, TektonService tekton, AuditService audit) {
        this.runs = runs;
        this.steps = steps;
        this.tekton = tekton;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.watch.pipeline-ms:4000}")
    public void poll() {
        for (CiRun run : runs.findByStatusIn(CiRun.ACTIVE)) {
            try {
                sync(run);
            } catch (Exception e) {
                log.warn("sync of run {} failed: {}", run.id, e.getMessage());
            }
        }
    }

    void sync(CiRun run) {
        if (run.pipelineRunName == null) return;
        GenericKubernetesResource pr = tekton.getPipelineRun(run.pipelineRunName);
        if (pr == null) {
            if (run.createdAt.isBefore(Instant.now().minus(Duration.ofMinutes(10)))) {
                finish(run, CiRun.FAILED, "PipelineRun disappeared");
            }
            return;
        }
        Object status = pr.getAdditionalProperties().get("status");
        if (status == null) return;

        run.startedAt = J.time(status, "startTime");
        for (Map<String, Object> child : J.list(status, "childReferences")) {
            if ("TaskRun".equals(child.get("kind"))) {
                syncTaskRun(run, J.str(child, "pipelineTaskName"), J.str(child, "name"));
            }
        }
        for (Map<String, Object> skipped : J.list(status, "skippedTasks")) {
            RunStep s = step(run.id, J.str(skipped, "name"), "-");
            s.status = "SKIPPED";
            steps.save(s);
        }

        Map<String, Object> cond = J.list(status, "conditions").stream()
                .filter(c -> "Succeeded".equals(c.get("type"))).findFirst().orElse(null);
        String condStatus = J.str(cond, "status");
        String reason = J.str(cond, "reason");
        if ("True".equals(condStatus)) {
            run.finishedAt = J.time(status, "completionTime");
            finish(run, CiRun.SUCCEEDED, J.str(cond, "message"));
        } else if ("False".equals(condStatus)) {
            run.finishedAt = J.time(status, "completionTime");
            boolean cancelled = reason != null && reason.startsWith("Cancelled");
            finish(run, cancelled ? CiRun.CANCELLED : CiRun.FAILED, J.str(cond, "message"));
        } else {
            run.status = CiRun.RUNNING;
            run.message = J.str(cond, "message");
            runs.save(run);
        }
    }

    private void syncTaskRun(CiRun run, String taskName, String taskRunName) {
        GenericKubernetesResource tr = tekton.getTaskRun(taskRunName);
        if (tr == null) return;
        Object status = tr.getAdditionalProperties().get("status");
        String pod = J.str(status, "podName");

        for (Map<String, Object> st : J.list(status, "steps")) {
            RunStep s = step(run.id, taskName, J.str(st, "name"));
            s.podName = pod;
            s.containerName = J.str(st, "container");
            if (st.get("terminated") != null) {
                Object exit = J.get(st, "terminated", "exitCode");
                s.exitCode = exit instanceof Number n ? n.intValue() : null;
                s.status = s.exitCode != null && s.exitCode == 0 ? "SUCCEEDED" : "FAILED";
                s.startedAt = J.time(st, "terminated", "startedAt");
                s.finishedAt = J.time(st, "terminated", "finishedAt");
            } else if (st.get("running") != null) {
                s.status = "RUNNING";
                s.startedAt = J.time(st, "running", "startedAt");
            } else {
                s.status = "WAITING";
            }
            steps.save(s);
        }

        for (Map<String, Object> r : J.list(status, "results")) {
            String name = J.str(r, "name"), value = J.str(r, "value");
            if (value == null) continue;
            value = value.trim();
            switch (name) {
                case "commit" -> run.commitSha = value;
                case "IMAGE_URL" -> run.imageUrl = value;
                case "IMAGE_DIGEST" -> run.imageDigest = value;
                case "SUMMARY" -> applyScanSummary(run, value);
                default -> { }
            }
        }
    }

    /** SUMMARY format written by trivy-scan: critical=0,high=2,medium=5,low=9 */
    static void applyScanSummary(CiRun run, String summary) {
        for (String part : summary.split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            Integer n;
            try { n = Integer.parseInt(kv[1].trim()); } catch (NumberFormatException e) { continue; }
            switch (kv[0].trim().toLowerCase()) {
                case "critical" -> run.vulnCritical = n;
                case "high" -> run.vulnHigh = n;
                case "medium" -> run.vulnMedium = n;
                case "low" -> run.vulnLow = n;
                default -> { }
            }
        }
    }

    private RunStep step(Long runId, String task, String step) {
        return steps.findByRunIdAndTaskNameAndStepName(runId, task, step).orElseGet(() -> {
            RunStep s = new RunStep();
            s.runId = runId;
            s.taskName = task;
            s.stepName = step;
            return s;
        });
    }

    private void finish(CiRun run, String status, String message) {
        run.status = status;
        run.message = message;
        if (run.finishedAt == null) run.finishedAt = Instant.now();
        runs.save(run);
        audit.record("system", "run." + status.toLowerCase(), String.valueOf(run.id), run.imageUrl);
    }
}
