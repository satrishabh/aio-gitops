package com.aio.platform.web;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.RunStep;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.RunStepRepo;
import com.aio.platform.service.RunService;
import com.aio.platform.service.TektonService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/runs")
public class RunController {
    private final CiRunRepo runs;
    private final RunStepRepo steps;
    private final ProjectRepo projects;
    private final TektonService tekton;
    private final RunService runService;
    private final ScheduledExecutorService streamer = Executors.newScheduledThreadPool(4);

    public RunController(CiRunRepo runs, RunStepRepo steps, ProjectRepo projects, TektonService tekton,
                         RunService runService) {
        this.runs = runs;
        this.steps = steps;
        this.projects = projects;
        this.tekton = tekton;
        this.runService = runService;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        CiRun run = runs.findById(id).orElseThrow();
        return Map.of(
                "run", run,
                "project", projects.findById(run.projectId).orElseThrow(),
                "steps", steps.findByRunIdOrderById(id));
    }

    /** Raw logs of one step container. */
    @GetMapping(value = "/{id}/steps/{stepId}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable Long id, @PathVariable Long stepId) {
        RunStep s = steps.findById(stepId).orElseThrow();
        if (!s.runId.equals(id)) throw new IllegalArgumentException("step does not belong to run");
        return tekton.logs(s.podName, s.containerName);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable Long id, @AuthenticationPrincipal OAuth2User user) {
        runService.cancel(runs.findById(id).orElseThrow(), Me.login(user));
    }

    /** Server-sent events: pushes run + steps every 2s until the run finishes. */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        SseEmitter emitter = new SseEmitter(60 * 60 * 1000L);
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        Runnable stop = () -> { if (task.get() != null) task.get().cancel(false); };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> stop.run());

        task.set(streamer.scheduleWithFixedDelay(() -> {
            try {
                CiRun run = runs.findById(id).orElseThrow();
                emitter.send(SseEmitter.event().name("run")
                        .data(Map.of("run", run, "steps", steps.findByRunIdOrderById(id)), MediaType.APPLICATION_JSON));
                if (!run.isActive()) emitter.complete();
            } catch (IOException | RuntimeException e) {
                emitter.completeWithError(e);
            }
        }, 0, 2, TimeUnit.SECONDS));
        return emitter;
    }
}
