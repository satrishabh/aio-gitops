package com.aio.platform.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps AIO under the AI provider's free-tier limits (Gemini counts requests per minute, input tokens per minute and
 * requests per day, per Google Cloud project; the day resets at midnight Pacific).
 *
 * <p>Two lanes share one budget:
 * <ul>
 *   <li>{@link Lane#INTERACTIVE}: a person is waiting. Never queued; over a limit it answers at once with
 *       "busy, try again in N s".</li>
 *   <li>{@link Lane#BACKGROUND}: scheduled jobs. They wait for a free slot (up to a maximum), and may use only
 *       {@code backgroundShare} of the daily requests, so a scheduled job can never use up people's quota.</li>
 * </ul>
 * A 429 from the provider starts a cool-down for everyone, so AIO stops sending until the provider says it may.
 * Limits are set a little below the provider's so other users of the same key do not tip it over.
 */
public class ProviderLimiter {
    public enum Lane { INTERACTIVE, BACKGROUND }

    /** Over a limit. {@code retryAfter} is how long until a slot frees up (or until the daily reset). */
    public static class Busy extends IllegalStateException {
        public final Duration retryAfter;

        Busy(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }
    }

    /** A granted request; {@link #settle} corrects its token estimate with the provider's count. */
    public static final class Ticket {
        private final Instant at;
        private int tokens;

        private Ticket(Instant at, int tokens) {
            this.at = at;
            this.tokens = tokens;
        }
    }

    /** Waits; replaced in tests so they need no real sleeping. */
    public interface Sleeper { void sleep(Duration d) throws InterruptedException; }

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final int rpm, tpm, rpd;
    private final double backgroundShare;
    private final ZoneId dayZone;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Deque<Ticket> lastMinute = new ArrayDeque<>();
    private LocalDate day;
    private int usedToday, backgroundToday;
    private Instant coolUntil = Instant.EPOCH;

    public ProviderLimiter(int rpm, int tpm, int rpd, double backgroundShare, ZoneId dayZone, Clock clock, Sleeper sleeper) {
        if (rpm < 1 || tpm < 1 || rpd < 1) throw new IllegalArgumentException("provider limits must be positive");
        this.rpm = rpm;
        this.tpm = tpm;
        this.rpd = rpd;
        this.backgroundShare = Math.max(0, Math.min(1, backgroundShare));
        this.dayZone = dayZone;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Rough token count before the call: about four characters per token, plus the whole answer budget. */
    public static int estimate(int promptChars, int maxOutputTokens) {
        return promptChars / 4 + maxOutputTokens;
    }

    /**
     * Takes a slot or throws {@link Busy}. Interactive calls never wait; background calls wait up to {@code maxWait}.
     */
    public Ticket acquire(Lane lane, int estimatedTokens, Duration maxWait) {
        Instant giveUp = clock.instant().plus(lane == Lane.INTERACTIVE ? Duration.ZERO : maxWait);
        while (true) {
            Duration wait;
            synchronized (this) {
                wait = tryAcquire(lane, estimatedTokens);
                if (wait.isZero()) return lastMinute.peekLast();
            }
            Instant now = clock.instant();
            if (lane == Lane.INTERACTIVE || now.plus(wait).isAfter(giveUp))
                throw new Busy("The AI provider's free limit is reached for now; try again in " + seconds(wait), wait);
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Busy("Interrupted while waiting for the AI provider", wait);
            }
        }
    }

    /** Replaces the estimate with the provider's real token count (input + output), when it reports one. */
    public synchronized void settle(Ticket ticket, Integer inputTokens, Integer outputTokens) {
        if (ticket == null || inputTokens == null) return;
        ticket.tokens = inputTokens + (outputTokens == null ? 0 : outputTokens);
    }

    /** The provider answered 429: send nothing until it says it is ready again. */
    public synchronized void cooldown(Duration retryAfter) {
        Duration d = retryAfter == null || retryAfter.isNegative() || retryAfter.isZero() ? MINUTE : retryAfter;
        Instant until = clock.instant().plus(d);
        if (until.isAfter(coolUntil)) coolUntil = until;
    }

    public synchronized java.util.Map<String, Object> usage() {
        rollDay(clock.instant());
        prune(clock.instant());
        return java.util.Map.of("requestsToday", usedToday, "requestsPerDay", rpd, "backgroundToday", backgroundToday,
                "backgroundPerDay", backgroundCap(), "requestsLastMinute", lastMinute.size(), "requestsPerMinute", rpm);
    }

    /** Zero when granted (and recorded), otherwise how long to wait. */
    private Duration tryAcquire(Lane lane, int estimatedTokens) {
        Instant now = clock.instant();
        rollDay(now);
        prune(now);
        if (now.isBefore(coolUntil)) return Duration.between(now, coolUntil);
        if (usedToday >= rpd || (lane == Lane.BACKGROUND && backgroundToday >= backgroundCap()))
            throw new Busy("The AI provider's daily free limit for " + (lane == Lane.BACKGROUND ? "scheduled jobs" : "AIO")
                    + " is used up; it resets at midnight " + dayZone.getId(), untilNextDay(now));
        int tokens = Math.min(estimatedTokens, tpm);   // one request larger than the budget must still be able to run
        if (lastMinute.size() >= rpm || tokensInWindow() + tokens > tpm)
            return Duration.between(now, lastMinute.peekFirst().at.plus(MINUTE)).plusMillis(50);
        lastMinute.addLast(new Ticket(now, tokens));
        usedToday++;
        if (lane == Lane.BACKGROUND) backgroundToday++;
        return Duration.ZERO;
    }

    private int backgroundCap() { return (int) Math.floor(rpd * backgroundShare); }

    private int tokensInWindow() {
        int sum = 0;
        for (Ticket t : lastMinute) sum += t.tokens;
        return sum;
    }

    private void prune(Instant now) {
        while (!lastMinute.isEmpty() && !lastMinute.peekFirst().at.plus(MINUTE).isAfter(now)) lastMinute.pollFirst();
    }

    private void rollDay(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, dayZone);
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
            backgroundToday = 0;
        }
    }

    private Duration untilNextDay(Instant now) {
        return Duration.between(now, LocalDate.ofInstant(now, dayZone).plusDays(1).atStartOfDay(dayZone).toInstant());
    }

    private static String seconds(Duration d) {
        long s = Math.max(1, (d.toMillis() + 999) / 1000);
        return s < 120 ? s + " s" : (s / 60) + " min";
    }
}
