package com.aio.platform.assistant;

import static org.mockito.Mockito.mock;

import com.aio.platform.service.AuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the golden questions through the real assistant and a real model, and writes
 * {@code target/assistant-eval.jsonl} (question, answer, retrieved contexts) for Ragas to score
 * (evals/assistant/ragas_eval.py). Skipped unless AIO_EVAL_BASE_URL and AIO_EVAL_MODEL are set; spends free-tier quota.
 */
@EnabledIfEnvironmentVariable(named = "AIO_EVAL_MODEL", matches = ".+")
class AssistantLiveEval {
    @Test
    void exportAnswersForRagas() throws Exception {
        String base = System.getenv("AIO_EVAL_BASE_URL"), model = System.getenv("AIO_EVAL_MODEL"),
                key = System.getenv("AIO_EVAL_API_KEY");
        KnowledgeBase kb = new KnowledgeBase();
        var llm = new OpenAiCompatibleClient(base, key, model,
                Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("AIO_EVAL_TIMEOUT_SECONDS", "60"))),
                System.getenv("AIO_EVAL_REASONING_EFFORT"));
        // free tiers cap requests per minute (Gemini Flash: about 10); pause between questions to stay under it
        long pauseMs = 1000L * Long.parseLong(System.getenv().getOrDefault("AIO_EVAL_PAUSE_SECONDS", "0"));
        var service = new AssistantService(kb, llm, mock(AuditService.class), Clock.systemUTC(), true, model,
                "eval", 2000, 800, 1000, 1000, 5);
        ObjectMapper json = new ObjectMapper();
        List<Map<String, String>> golden;
        try (InputStream in = getClass().getResourceAsStream("/assistant-golden.json")) {
            golden = json.readValue(in, new TypeReference<>() {});
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, String> g : golden) {
            if (pauseMs > 0 && !lines.isEmpty()) Thread.sleep(pauseMs);
            var answer = service.ask("eval", g.get("q"), List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_input", g.get("q"));
            row.put("response", answer.answer());
            row.put("retrieved_contexts", kb.search(Redactor.redact(g.get("q")) + " ", 5).stream()
                    .map(h -> Redactor.redact(h.chunk().text())).toList());
            row.put("grounded", answer.grounded());
            row.put("expected_source", g.get("source"));
            lines.add(json.writeValueAsString(row));
            Thread.sleep(2500);   // stay under free-tier requests-per-minute limits
        }
        // G2: one synthetic failed build, explained from metadata only (as with share-logs off)
        String facts = """
                Kind: CI build run
                Project: demo
                Status: FAILED
                Trigger: PUSH
                AIO message: build step failed
                Steps:
                - clone: SUCCEEDED
                - build / kaniko: FAILED (exit code 1)
                - scan / gate: SKIPPED
                """;
        var explained = service.explain("eval", "run:demo", "CI run #1 of demo", facts, "build failed kaniko dockerfile");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_input", "Explain why CI run #1 of demo failed and what to do next.");
        row.put("response", explained.answer());
        row.put("retrieved_contexts", List.of(facts));
        row.put("grounded", explained.grounded());
        row.put("expected_source", "aio-troubleshooting.md");
        lines.add(json.writeValueAsString(row));

        Path out = Path.of("target", "assistant-eval.jsonl");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
        System.out.println("wrote " + out.toAbsolutePath());
    }
}
