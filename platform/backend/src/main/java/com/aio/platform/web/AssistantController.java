package com.aio.platform.web;

import com.aio.platform.assistant.AssistantService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/** The "Ask AIO" assistant: read-only, grounded answers about the platform. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService assistant;
    private final com.aio.platform.assistant.FailureExplainer explainer;

    private final com.aio.platform.repo.AssistantReportRepo reports;

    public AssistantController(AssistantService assistant, com.aio.platform.assistant.FailureExplainer explainer,
                               com.aio.platform.repo.AssistantReportRepo reports) {
        this.assistant = assistant;
        this.explainer = explainer;
        this.reports = reports;
    }

    /** G6: the latest daily summary. It covers every project, so admins only. 404 when none was written yet. */
    @GetMapping("/reports/daily-digest")
    public com.aio.platform.domain.AssistantReport dailyDigest() {
        if (!com.aio.platform.security.UserScope.admin()) throw new SecurityException("Only admins can read the daily summary");
        return reports.findFirstByKindOrderByDayDesc("daily-digest").orElseThrow();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new java.util.LinkedHashMap<>(assistant.status());
        s.put("sharesLogs", explainer.sharesLogs());
        return s;
    }

    /** G2: likely cause and next steps for a failed CI run (read-only; projects you can see). */
    @PostMapping("/explain/run/{id}")
    public AssistantService.Answer explainRun(@PathVariable Long id, @AuthenticationPrincipal OAuth2User user) {
        return explainer.explainRun(Me.login(user), id);
    }

    /** G2: likely cause and next steps for a failed or paused deployment. */
    @PostMapping("/explain/promotion/{id}")
    public AssistantService.Answer explainPromotion(@PathVariable Long id, @AuthenticationPrincipal OAuth2User user) {
        return explainer.explainPromotion(Me.login(user), id);
    }

    public record AskRequest(String question, List<AssistantService.Turn> history) {}

    @PostMapping("/ask")
    public AssistantService.Answer ask(@RequestBody AskRequest req, @AuthenticationPrincipal OAuth2User user) {
        if (req == null) throw new IllegalArgumentException("Ask a question");
        return assistant.ask(Me.login(user), req.question(), req.history());
    }
}
