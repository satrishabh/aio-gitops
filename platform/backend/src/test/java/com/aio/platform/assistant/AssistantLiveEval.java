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
            if (pauseMs == 0) Thread.sleep(2500);   // stay under free-tier requests-per-minute limits
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
        if (pauseMs > 0) Thread.sleep(pauseMs);
        var explained = service.explain("eval", "run:demo", "CI run #1 of demo", facts, "build failed kaniko dockerfile");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_input", "Explain why CI run #1 of demo failed and what to do next.");
        row.put("response", explained.answer());
        row.put("retrieved_contexts", List.of(facts));
        row.put("grounded", explained.grounded());
        row.put("expected_source", "aio-troubleshooting.md");
        lines.add(json.writeValueAsString(row));

        // G6: the scheduled daily summary, from synthetic facts in the job's own format
        if (pauseMs > 0) Thread.sleep(pauseMs);
        var r1 = new com.aio.platform.domain.CiRun();
        r1.id = 41L; r1.projectId = 1L; r1.status = "SUCCEEDED"; r1.trigger = "PUSH";
        var r2 = new com.aio.platform.domain.CiRun();
        r2.id = 42L; r2.projectId = 2L; r2.status = "FAILED"; r2.trigger = "PUSH"; r2.message = "build step failed (kaniko exit code 1)";
        var r3 = new com.aio.platform.domain.CiRun();
        r3.id = 43L; r3.projectId = 2L; r3.status = "FAILED"; r3.trigger = "RERUN"; r3.message = "build step failed (kaniko exit code 1)";
        var p1 = new com.aio.platform.domain.Promotion();
        p1.id = 9L; p1.projectId = 1L; p1.fromEnv = "staging"; p1.toEnv = "prod"; p1.kind = "PROMOTE"; p1.status = "PAUSED";
        p1.requestedBy = "ann"; p1.message = "needs a decision: smoke test failed 2 of 4 samples";
        String digestFacts = DailyDigestJob.facts(List.of(r1, r2, r3), List.of(p1), id -> id == 1L ? "shop" : "blog");
        var digest = service.summarize("eval", DailyDigestJob.TASK, "Builds and deployments, last 24 hours", digestFacts, Duration.ZERO);
        Map<String, Object> drow = new LinkedHashMap<>();
        drow.put("user_input", "Write the morning summary of the last 24 hours of builds and deployments.");
        drow.put("response", digest.answer());
        drow.put("retrieved_contexts", List.of(digestFacts));
        drow.put("grounded", digest.grounded());
        drow.put("expected_source", "daily-digest");
        lines.add(json.writeValueAsString(drow));

        Path out = Path.of("target", "assistant-eval.jsonl");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
        System.out.println("wrote " + out.toAbsolutePath());
    }
}
