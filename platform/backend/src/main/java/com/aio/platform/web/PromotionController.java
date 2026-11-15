package com.aio.platform.web;

import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import com.aio.platform.saga.Saga;
import com.aio.platform.saga.SagaEngine;
import com.aio.platform.saga.SagaEvent;
import com.aio.platform.saga.SagaInstance;
import com.aio.platform.service.FreezePolicy;
import com.aio.platform.service.PromotionSaga;
import com.aio.platform.service.PromotionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/promotions")
public class PromotionController {
    private final PromotionRepo promotions;
    private final ProjectRepo projects;
    private final PromotionService service;
    private final SagaEngine sagas;
    private final FreezePolicy freeze;

    public PromotionController(PromotionRepo promotions, ProjectRepo projects, PromotionService service,
                               SagaEngine sagas, FreezePolicy freeze) {
        this.promotions = promotions;
        this.projects = projects;
        this.service = service;
        this.sagas = sagas;
        this.freeze = freeze;
    }

    @GetMapping
    public List<Promotion> list(@RequestParam(required = false) String status) {
        if (!com.aio.platform.security.UserScope.admin()) return promotions.findVisible(
                com.aio.platform.security.UserScope.projectIds(projects), status, PageRequest.of(0, 200));
        return status == null
                ? promotions.findAllByOrderByIdDesc(PageRequest.of(0, 200))
                : promotions.findByStatusOrderByIdDesc(status);
    }

    @PostMapping("/{id}/approve")
    public Promotion approve(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean overrideFreeze,
                             @AuthenticationPrincipal OAuth2User user) {
        Promotion pr = promotions.findById(id).orElseThrow();
        return service.approve(projects.findById(pr.projectId).orElseThrow(), pr, Me.login(user), overrideFreeze);
    }

    /**
     * The deployment's execution timeline (like a Harness execution view): every step with its state, and every
     * event (waits, retries, failures, decisions, compensations) with its time.
     */
    @GetMapping("/{id}/execution")
    public Map<String, Object> execution(@PathVariable Long id) {
        Promotion pr = visible(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("promotion", pr);
        SagaInstance s = pr.sagaId == null ? null : sagas.find(PromotionSaga.key(pr)).orElse(null);
        if (s == null) return out;
        List<SagaEvent> history = sagas.history(s.id);
        List<String> names = sagas.stepNames(s.type);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("name", n);
            st.put("state", stepState(s, i, n, history));
            steps.add(st);
        }
        Map<String, Object> saga = new LinkedHashMap<>();
        saga.put("id", s.id);
        saga.put("status", s.status);
        saga.put("lastError", s.lastError);
        saga.put("nextAttemptAt", s.nextAttemptAt);
        saga.put("deadlineAt", s.deadlineAt);
        saga.put("createdAt", s.createdAt);
        saga.put("updatedAt", s.updatedAt);
        out.put("saga", saga);
        out.put("steps", steps);
        out.put("events", history.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.at);
            m.put("step", e.step);
            m.put("outcome", e.outcome);
            m.put("message", e.message);
            return m;
        }).toList());
        return out;
    }

    /** Pending, running, done, failed, skipped, paused, compensated, for one step of the timeline. */
    static String stepState(SagaInstance s, int i, String name, List<SagaEvent> history) {
        boolean compensated = history.stream().anyMatch(e -> name.equals(e.step) && "COMPENSATED".equals(e.outcome));
        if (compensated) return "compensated";
        boolean skipped = history.stream().anyMatch(e -> name.equals(e.step) && "SKIPPED".equals(e.outcome));
        if (skipped) return "skipped";
        boolean done = history.stream().anyMatch(e -> name.equals(e.step) && "DONE".equals(e.outcome));
        if (done) return SagaInstance.COMPENSATING.equals(s.status) && i == s.step ? "undoing" : "done";
        if (i == s.step) {
            return switch (s.status) {
                case SagaInstance.RUNNING -> "running";
                case SagaInstance.PAUSED -> "paused";
                case SagaInstance.FAILED -> "failed";
                default -> "pending";
            };
        }
        boolean failed = history.stream().anyMatch(e -> name.equals(e.step) && ("FAIL".equals(e.outcome) || "TIMEOUT".equals(e.outcome)));
        return failed ? "failed" : "pending";
    }

    public record InterveneRequest(String action) {}

    /** An approver's decision on a paused deployment (retry / skip / rollback) or a failed revert (retry / resolve). */
    @PostMapping("/{id}/intervene")
    public Promotion intervene(@PathVariable Long id, @RequestBody InterveneRequest req, @AuthenticationPrincipal OAuth2User user) {
        Promotion pr = visible(id);
        Saga.Action action;
        try {
            action = Saga.Action.valueOf(req == null || req.action() == null ? "" : req.action().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("action must be retry, skip, rollback or resolve");
        }
        return service.intervene(projects.findById(pr.projectId).orElseThrow(), pr, action, Me.login(user));
    }

    /** Configured deployment freeze windows and whether each is in force now. */
    @GetMapping("/freeze")
    public List<Map<String, Object>> freezeWindows() {
        return freeze.describe();
    }

    private Promotion visible(Long id) {
        Promotion pr = promotions.findById(id).orElseThrow();
        if (!com.aio.platform.security.UserScope.admin()
                && !com.aio.platform.security.UserScope.projectIds(projects).contains(pr.projectId))
            throw new java.util.NoSuchElementException();
        return pr;
    }

    public record RejectRequest(String reason) {}

    @PostMapping("/{id}/reject")
    public Promotion reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest req,
                            @AuthenticationPrincipal OAuth2User user) {
        Promotion pr = promotions.findById(id).orElseThrow();
        return service.reject(pr, Me.login(user), req == null ? null : req.reason());
    }
}
