package com.aio.platform.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Several models, tried in order. Free tiers limit each model separately (Gemini: requests per day per model), so
 * when one model is used up or stalls, the next one answers. A model that answered 429 is skipped until the time the
 * provider gave (for a daily limit that is hours); one that timed out or failed is simply skipped for this call.
 * Only when every model is cooling down does the caller get {@link LlmClient.RateLimited}, with the earliest time.
 */
public class FallbackLlmClient implements LlmClient {
    private static final Duration DEFAULT_COOL = Duration.ofMinutes(1);

    private final Map<String, LlmClient> models = new LinkedHashMap<>();
    private final Map<String, Instant> coolUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final Clock clock;

    public FallbackLlmClient(List<String> modelIds, Function<String, LlmClient> factory, Clock clock) {
        if (modelIds.isEmpty()) throw new IllegalArgumentException("at least one model");
        modelIds.forEach(m -> models.put(m, factory.apply(m)));
        this.clock = clock;
    }

    /** "a, b" -> [a, b]; blanks dropped. */
    public static List<String> parse(String models) {
        List<String> out = new ArrayList<>();
        if (models != null) for (String m : models.split(",")) if (!m.isBlank()) out.add(m.trim());
        return out;
    }

    /** One client for one model, or a fallback chain for a comma-separated list. */
    public static LlmClient of(String models, Function<String, LlmClient> factory, Clock clock) {
        List<String> ids = parse(models);
        return ids.size() == 1 ? factory.apply(ids.get(0)) : new FallbackLlmClient(ids, factory, clock);
    }

    @Override
    public Reply complete(List<Message> messages, int maxTokens) {
        Instant now = clock.instant();
        RuntimeException last = null;
        Instant earliest = null;
        for (Map.Entry<String, LlmClient> m : models.entrySet()) {
            Instant until = coolUntil.get(m.getKey());
            if (until != null && until.isAfter(now)) {
                if (earliest == null || until.isBefore(earliest)) earliest = until;
                continue;
            }
            try {
                return m.getValue().complete(messages, maxTokens);
            } catch (RateLimited e) {
                Instant u = now.plus(e.retryAfter == null ? DEFAULT_COOL : e.retryAfter);
                coolUntil.put(m.getKey(), u);
                if (earliest == null || u.isBefore(earliest)) earliest = u;
                last = e;
            } catch (IllegalStateException e) {
                last = e;   // timed out or failed: try the next model, no cool-down
            }
        }
        // every model is resting: tell the caller when the first one is back; otherwise report the real failure
        if (coolingCount(now) == models.size() || last == null)
            throw new RateLimited(earliest == null ? DEFAULT_COOL : Duration.between(now, earliest));
        throw last;
    }

    private long coolingCount(Instant now) {
        return coolUntil.values().stream().filter(u -> u.isAfter(now)).count();
    }

    /** For the status page: which models are resting, and until when. */
    public Map<String, Instant> cooling() {
        Instant now = clock.instant();
        Map<String, Instant> out = new LinkedHashMap<>();
        coolUntil.forEach((m, u) -> { if (u.isAfter(now)) out.put(m, u); });
        return out;
    }
}
