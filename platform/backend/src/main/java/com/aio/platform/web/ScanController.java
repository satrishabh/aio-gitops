package com.aio.platform.web;

import com.aio.platform.domain.ScanFinding;
import com.aio.platform.domain.ScanRun;
import com.aio.platform.domain.ScanStep;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.ScanFindingRepo;
import com.aio.platform.repo.ScanRunRepo;
import com.aio.platform.repo.ScanStepRepo;
import com.aio.platform.service.ScanService;
import com.aio.platform.service.TektonService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Standalone Trivy security scans: start / list / follow live / findings / download / re-run / cancel. */
@RestController
@RequestMapping("/api/scans")
public class ScanController {
    private final ScanRunRepo scans;
    private final ScanStepRepo steps;
    private final ScanFindingRepo findings;
    private final ProjectRepo projects;
    private final ScanService service;
    private final TektonService tekton;
    private final ScheduledExecutorService streamer = Executors.newScheduledThreadPool(2);

    public ScanController(ScanRunRepo scans, ScanStepRepo steps, ScanFindingRepo findings, ProjectRepo projects,
                          ScanService service, TektonService tekton) {
        this.scans = scans;
        this.steps = steps;
        this.findings = findings;
        this.projects = projects;
        this.service = service;
        this.tekton = tekton;
    }

    @PostMapping
    public ScanRun start(@RequestBody ScanService.StartRequest req, @AuthenticationPrincipal OAuth2User user) {
        return service.start(req, Me.login(user));
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long projectId,
                                          @RequestParam(defaultValue = "50") int limit) {
        var page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        var list = projectId == null ? scans.findAllByOrderByIdDesc(page) : scans.findByProjectIdOrderByIdDesc(projectId, page);
        return list.stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        ScanRun s = scans.findById(id).orElseThrow();
        Map<String, Object> out = summary(s);
        out.put("steps", steps.findByRunIdOrderById(id));
        return out;
    }

    /** Live updates (scan + steps every 2s) until the scan finishes. */
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
                ScanRun s = scans.findById(id).orElseThrow();
                Map<String, Object> body = summary(s);
                body.put("steps", steps.findByRunIdOrderById(id));
                emitter.send(SseEmitter.event().name("scan").data(body, MediaType.APPLICATION_JSON));
                if (!s.isActive()) emitter.complete();
            } catch (IOException | RuntimeException e) {
                emitter.completeWithError(e);
            }
        }, 0, 2, TimeUnit.SECONDS));
        return emitter;
    }

    @GetMapping(value = "/{id}/steps/{stepId}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable Long id, @PathVariable Long stepId) {
        ScanStep st = steps.findById(stepId).orElseThrow();
        if (!st.runId.equals(id)) throw new IllegalArgumentException("step does not belong to scan");
        String text = st.logText != null ? st.logText : tekton.logs(st.podName, st.containerName);
        // The report step is one huge JSON line (shown in the findings table instead); keep the terminal readable
        if ("report".equals(st.stepName) && text.length() > 20_000)
            return text.substring(0, 20_000) + "\n… (" + text.length() + " chars - see the Findings tab or download the report)";
        return text;
    }

    @GetMapping("/{id}/findings")
    public Map<String, Object> findings(@PathVariable Long id, @RequestParam(defaultValue = "") String scanner,
                                        @RequestParam(defaultValue = "") String severity, @RequestParam(defaultValue = "") String q,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        scans.findById(id).orElseThrow();
        String query = q.trim().toLowerCase(java.util.Locale.ROOT);
        if (query.length() > 100) query = query.substring(0, 100);
        var result = findings.search(id, scanner.trim(), severity.trim().toUpperCase(java.util.Locale.ROOT), query,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 500))));
        return Map.of("items", result.getContent(), "total", result.getTotalElements(), "page", result.getNumber(),
                "pages", result.getTotalPages());
    }

    /** All findings as JSON (download). */
    @GetMapping("/{id}/report.json")
    public ResponseEntity<Map<String, Object>> report(@PathVariable Long id) {
        ScanRun s = scans.findById(id).orElseThrow();
        List<ScanFinding> all = findings.findByScanIdOrderById(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scan", summary(s));
        body.put("findings", all);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"security-scan-" + id + ".json\"")
                .body(body);
    }

    @PostMapping("/{id}/rerun")
    public ScanRun rerun(@PathVariable Long id, @AuthenticationPrincipal OAuth2User user) {
        return service.rerun(scans.findById(id).orElseThrow(), Me.login(user));
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable Long id, @AuthenticationPrincipal OAuth2User user) {
        service.cancel(scans.findById(id).orElseThrow(), Me.login(user));
    }

    private Map<String, Object> summary(ScanRun s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scan", s);
        m.put("summary", com.aio.platform.service.ScanWatcher.readSummary(s.summary));
        m.put("project", s.projectId == null ? null : projects.findById(s.projectId).map(p -> Map.of("id", p.id, "name", p.name)).orElse(null));
        String r = s.repoUrl.replaceFirst("^https://github\\.com/", "").replaceFirst("\\.git$", "");
        m.put("repo", r);
        return m;
    }
}
