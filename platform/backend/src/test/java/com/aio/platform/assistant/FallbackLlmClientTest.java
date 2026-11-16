package com.aio.platform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FallbackLlmClientTest {
    private final ProviderLimiterTest.MutableClock clock = new ProviderLimiterTest.MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final List<String> called = new ArrayList<>();
    private final Map<String, RuntimeException> failing = new HashMap<>();

    private LlmClient fake(String model) {
        return (messages, max) -> {
            called.add(model);
            RuntimeException e = failing.get(model);
            if (e != null) throw e;
            return new LlmClient.Reply("ok from " + model, model, 1, 1);
        };
    }

    private final List<LlmClient.Message> msg = List.of(new LlmClient.Message("user", "hi"));

    @Test
    void oneModelNeedsNoWrapper() {
        assertThat(FallbackLlmClient.of("gemini-3.1-flash-lite", this::fake, clock)).isNotInstanceOf(FallbackLlmClient.class);
        assertThat(FallbackLlmClient.parse(" a , ,b ")).containsExactly("a", "b");
    }

    @Test
    void aModelOutOfDailyQuotaRestsUntilTheProviderSaysAndTheNextAnswers() {
        var c = FallbackLlmClient.of("lite,flash", this::fake, clock);
        failing.put("lite", new LlmClient.RateLimited(Duration.ofHours(4)));
        assertThat(c.complete(msg, 10).text()).isEqualTo("ok from flash");
        called.clear();
        clock.advance(Duration.ofHours(1));
        assertThat(c.complete(msg, 10).text()).isEqualTo("ok from flash");
        assertThat(called).containsExactly("flash");   // lite is not asked again while it rests
        failing.clear();
        clock.advance(Duration.ofHours(3));
        assertThat(c.complete(msg, 10).text()).isEqualTo("ok from lite");
    }

    @Test
    void aStalledModelIsSkippedForThisCallOnly() {
        var c = FallbackLlmClient.of("slow,flash", this::fake, clock);
        failing.put("slow", new IllegalStateException("The AI provider did not answer in time"));
        assertThat(c.complete(msg, 10).text()).isEqualTo("ok from flash");
        failing.clear();
        assertThat(c.complete(msg, 10).text()).isEqualTo("ok from slow");
    }

    @Test
    void whenEveryModelRestsTheCallerLearnsTheEarliestReturn() {
        var c = (FallbackLlmClient) FallbackLlmClient.of("lite,flash", this::fake, clock);
        failing.put("lite", new LlmClient.RateLimited(Duration.ofHours(4)));
        failing.put("flash", new LlmClient.RateLimited(Duration.ofMinutes(30)));
        var e = org.junit.jupiter.api.Assertions.assertThrows(LlmClient.RateLimited.class, () -> c.complete(msg, 10));
        assertThat(e.retryAfter).isEqualTo(Duration.ofMinutes(30));
        assertThat(c.cooling()).containsOnlyKeys("lite", "flash");
    }

    @Test
    void otherFailuresAreReportedAsTheyAre() {
        var c = FallbackLlmClient.of("a,b", this::fake, clock);
        failing.put("a", new IllegalStateException("The AI provider answered HTTP 400"));
        failing.put("b", new IllegalStateException("The AI provider answered HTTP 400"));
        assertThatThrownBy(() -> c.complete(msg, 10)).isNotInstanceOf(LlmClient.RateLimited.class).hasMessageContaining("HTTP 400");
    }

    @Test
    void googleRetryDelayIsReadFromTheBody() {
        var none = new org.springframework.http.HttpHeaders();
        assertThat(OpenAiCompatibleClient.retryAfter(none, "{\"details\":[{\"retryDelay\": \"17562s\"}]}"))
                .isEqualTo(Duration.ofSeconds(17562));
        assertThat(OpenAiCompatibleClient.retryAfter(none, "Generate content requests per day limit exceeded. Please retry in 4h52m42.123s."))
                .isEqualTo(Duration.ofSeconds(4 * 3600 + 52 * 60 + 43));
        assertThat(OpenAiCompatibleClient.retryAfter(none, "Please retry in 12.5s.")).isEqualTo(Duration.ofSeconds(13));
        assertThat(OpenAiCompatibleClient.retryAfter(none, "no hint here")).isNull();
    }
}
