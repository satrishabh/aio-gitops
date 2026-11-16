package com.aio.platform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aio.platform.assistant.ProviderLimiter.Lane;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderLimiterTest {
    static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant start) { now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    // 10:00 UTC = 02:00 Pacific, so the Pacific day is well inside the test
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final List<Duration> slept = new ArrayList<>();
    private final ProviderLimiter.Sleeper sleeper = d -> { slept.add(d); clock.advance(d); };

    private ProviderLimiter limiter(int rpm, int tpm, int rpd, double share) {
        return new ProviderLimiter(rpm, tpm, rpd, share, ZoneId.of("America/Los_Angeles"), clock, sleeper);
    }

    @Test
    void peopleAreNeverQueuedTheyAreToldWhenToRetry() {
        var l = limiter(2, 1_000_000, 100, 0.25);
        l.acquire(Lane.INTERACTIVE, 100, Duration.ZERO);
        clock.advance(Duration.ofSeconds(10));
        l.acquire(Lane.INTERACTIVE, 100, Duration.ZERO);
        assertThatThrownBy(() -> l.acquire(Lane.INTERACTIVE, 100, Duration.ZERO))
                .isInstanceOf(ProviderLimiter.Busy.class).hasMessageContaining("try again in 51 s");
        assertThat(slept).isEmpty();
        clock.advance(Duration.ofSeconds(51));
        l.acquire(Lane.INTERACTIVE, 100, Duration.ZERO);   // the first request left the one-minute window
    }

    @Test
    void scheduledJobsWaitForAFreeSlot() {
        var l = limiter(1, 1_000_000, 100, 0.5);
        l.acquire(Lane.BACKGROUND, 100, Duration.ofMinutes(5));
        l.acquire(Lane.BACKGROUND, 100, Duration.ofMinutes(5));
        assertThat(slept).hasSize(1);
        assertThat(slept.get(0)).isBetween(Duration.ofSeconds(60), Duration.ofSeconds(61));
    }

    @Test
    void scheduledJobsGiveUpAfterTheirMaximumWait() {
        var l = limiter(1, 1_000_000, 100, 0.5);
        l.acquire(Lane.BACKGROUND, 100, Duration.ofMinutes(5));
        assertThatThrownBy(() -> l.acquire(Lane.BACKGROUND, 100, Duration.ofSeconds(30))).isInstanceOf(ProviderLimiter.Busy.class);
    }

    @Test
    void tokensPerMinuteAreCountedAndCorrectedWithTheRealUsage() {
        var l = limiter(100, 10_000, 100, 0.25);
        var t = l.acquire(Lane.INTERACTIVE, 9_000, Duration.ZERO);
        assertThatThrownBy(() -> l.acquire(Lane.INTERACTIVE, 2_000, Duration.ZERO)).isInstanceOf(ProviderLimiter.Busy.class);
        l.settle(t, 1_500, 300);   // the estimate counted the whole answer budget; the provider reports less
        l.acquire(Lane.INTERACTIVE, 2_000, Duration.ZERO);
    }

    @Test
    void scheduledJobsCannotUseUpPeoplesDailyQuota() {
        var l = limiter(100, 1_000_000, 8, 0.25);   // background may use 2 of 8
        l.acquire(Lane.BACKGROUND, 10, Duration.ZERO);
        l.acquire(Lane.BACKGROUND, 10, Duration.ZERO);
        assertThatThrownBy(() -> l.acquire(Lane.BACKGROUND, 10, Duration.ofHours(1)))
                .isInstanceOf(ProviderLimiter.Busy.class).hasMessageContaining("scheduled jobs");
        for (int i = 0; i < 6; i++) l.acquire(Lane.INTERACTIVE, 10, Duration.ZERO);
        assertThatThrownBy(() -> l.acquire(Lane.INTERACTIVE, 10, Duration.ZERO)).hasMessageContaining("daily free limit");
        assertThat(l.usage()).containsEntry("requestsToday", 8).containsEntry("backgroundToday", 2);
    }

    @Test
    void theDailyLimitResetsAtMidnightPacific() {
        var l = limiter(100, 1_000_000, 1, 0.25);
        l.acquire(Lane.INTERACTIVE, 10, Duration.ZERO);
        var busy = org.junit.jupiter.api.Assertions.assertThrows(ProviderLimiter.Busy.class,
                () -> l.acquire(Lane.INTERACTIVE, 10, Duration.ZERO));
        assertThat(busy.retryAfter).isEqualTo(Duration.ofHours(22));   // 02:00 to 24:00 Pacific
        clock.advance(Duration.ofHours(22));
        l.acquire(Lane.INTERACTIVE, 10, Duration.ZERO);
    }

    @Test
    void aProvider429StopsEveryoneUntilItsRetryAfter() {
        var l = limiter(100, 1_000_000, 100, 0.25);
        l.cooldown(Duration.ofSeconds(30));
        assertThatThrownBy(() -> l.acquire(Lane.INTERACTIVE, 10, Duration.ZERO)).hasMessageContaining("30 s");
        l.acquire(Lane.BACKGROUND, 10, Duration.ofMinutes(1));   // waits it out
        assertThat(slept).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void aSingleOversizedRequestCanStillRun() {
        var l = limiter(100, 1_000, 100, 0.25);
        l.acquire(Lane.INTERACTIVE, 50_000, Duration.ZERO);
    }
}
