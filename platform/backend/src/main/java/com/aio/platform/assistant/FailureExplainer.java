package com.aio.platform.assistant;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.domain.RunStep;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import com.aio.platform.repo.RunStepRepo;
import com.aio.platform.saga.SagaEngine;
import com.aio.platform.saga.SagaEvent;
import com.aio.platform.security.UserScope;
import com.aio.platform.service.TektonService;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Slice G2 "Explain a failure": gathers what AIO already knows about one failed CI run or deployment and asks the
 * assistant for the likely cause and next steps. Read-only; only for projects the person can see.
 *
 * <p>By default only metadata is shared (status, failed step names, exit codes, AIO's own error messages, the
 * deployment's step history). Build logs can hold anything a build prints, so their last lines are included only
 * when an admin sets {@code AIO_ASSISTANT_SHARE_LOGS=true}, and then only after redaction and with a size cap.
 */
@Service
public class FailureExplainer {
    static final int LOG_LINES = 60;
    private static final Set<String> FAILED_PROMOTION = Set.of(Promotion.FAILED, Promotion.PAUSED);

    private final AssistantService assistant;
    private final ProjectRepo projects;
    private final CiRunRepo runs;
    private final RunStepRepo steps;
    private final PromotionRepo promotions;
    private final SagaEngine sagas;
    private final TektonService tekton;
    private final boolean shareLogs;

    public FailureExplainer(AssistantService assistant, ProjectRepo projects, CiRunRepo runs, RunStepRepo steps,
                            PromotionRepo promotions, SagaEngine sagas, TektonService tekton,
                            @Value("${app.assistant.share-logs:false}") boolean shareLogs) {
        this.assistant = assistant;
        this.projects = projects;
        this.runs = runs;
        this.steps = steps;
        this.promotions = promotions;
        this.sagas = sagas;
        this.tekton = tekton;
        this.shareLogs = shareLogs;
    }

    public boolean sharesLogs() { return shareLogs; }

    public AssistantService.Answer explainRun(String actor, Long runId) {
        CiRun run = runs.findById(runId).orElseThrow();
        Project p = UserScope.project(projects, run.projectId);
        if (!"FAILED".equals(run.status) && !"FAILED".equals(run.releaseStatus))
            throw new IllegalArgumentException("Only failed runs can be explained (this one is " + run.status + ")");
        List<RunStep> all = steps.findByRunIdOrderById(runId);
        String facts = runFacts(p, run, all, shareLogs ? this::logTail : s -> null);
        return assistant.explain(actor, "run:" + runId, "CI run #" + runId + " of " + p.name, facts, hint(run, all));
    }

    public AssistantService.Answer explainPromotion(String actor, Long promotionId) {
        Promotion pr = promotions.findById(promotionId).orElseThrow();
        Project p = UserScope.project(projects, pr.projectId);
        if (!FAILED_PROMOTION.contains(pr.status))
            throw new IllegalArgumentException("Only failed or paused deployments can be explained (this one is " + pr.status + ")");
        List<SagaEvent> history = pr.sagaId == null ? List.of() : sagas.history(pr.sagaId);
        String facts = promotionFacts(p, pr, history);
        String hint = pr.kind + " " + pr.toEnv + " " + (pr.message == null ? "" : pr.message) + " "
                + String.join(" ", history.stream().map(e -> e.step == null ? "" : e.step).distinct().toList());
        return assistant.explain(actor, "promotion:" + promotionId, "Deployment #" + promotionId + " of " + p.name, facts, hint);
    }

    // ------------------------------------------------------------------ facts (pure, unit-tested)

    static String runFacts(Project p, CiRun run, List<RunStep> all, java.util.function.Function<RunStep, String> logs) {
        StringBuilder f = new StringBuilder();
        f.append("Kind: CI build run\nProject: ").append(p.name).append('\n');
        f.append("Status: ").append(run.status).append(run.releaseStatus == null ? "" : " (release " + run.releaseStatus + ")").append('\n');
        if (run.trigger != null) f.append("Trigger: ").append(run.trigger).append('\n');
        if (run.gitRef != null) f.append("Git ref: ").append(run.gitRef).append('\n');
        if (run.message != null) f.append("AIO message: ").append(run.message).append('\n');
        if (run.releaseMessage != null) f.append("Release message: ").append(run.releaseMessage).append('\n');
        if (run.vulnCritical != null || run.vulnHigh != null)
            f.append("Vulnerabilities found: critical=").append(run.vulnCritical).append(" high=").append(run.vulnHigh)
                    .append(" (gate: ").append(run.scanSeverity).append(")\n");
        f.append("Steps:\n");
        for (RunStep s : all) {
            f.append("- ").append(s.label()).append(": ").append(s.status);
            if (s.exitCode != null && s.exitCode != 0) f.append(" (exit code ").append(s.exitCode).append(')');
            f.append('\n');
        }
        for (RunStep s : all) {
            if (!"FAILED".equals(s.status)) continue;
            String tail = logs.apply(s);
            if (tail != null && !tail.isBlank()) f.append("Last log lines of ").append(s.label()).append(":\n").append(tail).append('\n');
        }
        return f.toString();
    }

    static String promotionFacts(Project p, Promotion pr, List<SagaEvent> history) {
        StringBuilder f = new StringBuilder();
        f.append("Kind: deployment (").append(pr.kind.toLowerCase(Locale.ROOT)).append(")\nProject: ").append(p.name).append('\n');
        f.append("Target environment: ").append(pr.toEnv).append("\nImage tag: ").append(pr.imageTag).append('\n');
        if (pr.previousTag != null) f.append("Previous tag: ").append(pr.previousTag).append('\n');
        f.append("Status: ").append(pr.status).append('\n');
        if (pr.message != null) f.append("AIO message: ").append(pr.message).append('\n');
        f.append("Step history (oldest first):\n");
        List<SagaEvent> last = history.size() > 25 ? history.subList(history.size() - 25, history.size()) : history;
        for (SagaEvent e : last) {
            f.append("- ").append(e.outcome);
            if (e.step != null) f.append(" ").append(e.step);
            if (e.message != null) f.append(": ").append(AssistantService.clip(e.message, 300));
            f.append('\n');
        }
        return f.toString();
    }

    /** Words that point retrieval at the right docs: failed step names and error-looking lines. */
    static String hint(CiRun run, List<RunStep> all) {
        StringBuilder h = new StringBuilder("build failed ");
        if (run.message != null) h.append(run.message).append(' ');
        if (run.vulnCritical != null && run.vulnCritical > 0) h.append("vulnerability scan gate ");
        for (RunStep s : all) if ("FAILED".equals(s.status)) h.append(s.taskName).append(' ').append(s.stepName).append(' ');
        return h.toString();
    }

    private String logTail(RunStep s) {
        String text = s.logText != null ? s.logText : tekton.logs(s.podName, s.containerName);
        if (text == null) return null;
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - LOG_LINES);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
