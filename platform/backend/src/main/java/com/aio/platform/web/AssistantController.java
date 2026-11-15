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

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return assistant.status();
    }

    public record AskRequest(String question, List<AssistantService.Turn> history) {}

    @PostMapping("/ask")
    public AssistantService.Answer ask(@RequestBody AskRequest req, @AuthenticationPrincipal OAuth2User user) {
        if (req == null) throw new IllegalArgumentException("Ask a question");
        return assistant.ask(Me.login(user), req.question(), req.history());
    }
}
