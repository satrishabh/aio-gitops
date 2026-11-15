package com.aio.platform.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment freeze windows (Harness "deployment freeze", change-management blackout periods).
 * While a window covers an environment, nothing new is deployed there: promotions and rollbacks are not applied,
 * and prod sync / values edits are refused. Requests can still be raised and wait as PENDING. An AIO admin can
 * override a freeze for one approval (break-glass), which is audited.
 *
 * <p>Configured with {@code AIO_FREEZE_WINDOWS}: entries separated by {@code ;}, each
 * {@code <envs>@<when>[#reason]} where envs is a comma list or {@code *}, and when is either
 * <ul>
 *   <li>an absolute range {@code 2026-12-24T18:00/2026-12-27T09:00}, or</li>
 *   <li>a weekly range {@code FRI 18:00/MON 09:00} (may wrap over the weekend),</li>
 * </ul>
 * in {@code AIO_FREEZE_ZONE} (default Asia/Kolkata). Example:
 * {@code prod@FRI 18:00/MON 09:00#weekend; prod,staging@2026-12-24T00:00/2026-12-27T00:00#holidays}.
 * A malformed value stops startup, so a typo never silently disables a freeze.
 */
@Component
public class FreezePolicy {
    /** An active freeze on an environment. */
    public record Freeze(String env, String reason, Instant until) {
        public String describe() {
            return env + " is frozen until " + until + (reason == null ? "" : " (" + reason + ")");
        }
    }

    sealed interface When permits Absolute, Weekly {
        /** End of the occurrence covering {@code t}, or null when {@code t} is outside the window. */
        ZonedDateTime endIfCovers(ZonedDateTime t);

        String describe();
    }

    record Absolute(ZonedDateTime from, ZonedDateTime to) implements When {
        public ZonedDateTime endIfCovers(ZonedDateTime t) {
            return !t.isBefore(from) && t.isBefore(to) ? to : null;
        }

        public String describe() { return from.toLocalDateTime() + " to " + to.toLocalDateTime(); }
    }

    record Weekly(DayOfWeek fromDay, LocalTime fromTime, DayOfWeek toDay, LocalTime toTime) implements When {
        public ZonedDateTime endIfCovers(ZonedDateTime t) {
            int m = minuteOfWeek(t.getDayOfWeek(), t.toLocalTime()), s = minuteOfWeek(fromDay, fromTime), e = minuteOfWeek(toDay, toTime);
            boolean in = s <= e ? m >= s && m < e : m >= s || m < e;
            if (!in) return null;
            ZonedDateTime end = t.with(TemporalAdjusters.nextOrSame(toDay)).with(toTime).withSecond(0).withNano(0);
            return end.isAfter(t) ? end : end.plusWeeks(1);
        }

        public String describe() {
            return "every " + day(fromDay) + " " + fromTime + " to " + day(toDay) + " " + toTime;
        }

        private static int minuteOfWeek(DayOfWeek d, LocalTime t) {
            return (d.getValue() - 1) * 1440 + t.getHour() * 60 + t.getMinute();
        }

        private static String day(DayOfWeek d) {
            return d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        }
    }

    record Window(Set<String> envs, When when, String reason) {
        boolean appliesTo(String env) { return envs.contains("*") || envs.contains(env); }
    }

    private final List<Window> windows;
    private final ZoneId zone;
    private final Clock clock;

    public FreezePolicy(@Value("${app.freeze.windows:}") String spec, @Value("${app.freeze.zone:Asia/Kolkata}") String zone,
                        ObjectProvider<Clock> clock) {
        this.zone = ZoneId.of(zone);
        this.windows = parse(spec, this.zone);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** No freeze configured (tests and defaults). */
    public static FreezePolicy none() {
        return new FreezePolicy("", "UTC", new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(Clock.class));
    }

    /** The freeze covering {@code env} now; when several overlap, the one lasting longest. */
    public Optional<Freeze> active(String env) {
        ZonedDateTime now = clock.instant().atZone(zone);
        Freeze best = null;
        for (Window w : windows) {
            if (!w.appliesTo(env)) continue;
            ZonedDateTime end = w.when().endIfCovers(now);
            if (end != null && (best == null || end.toInstant().isAfter(best.until())))
                best = new Freeze(env, w.reason(), end.toInstant());
        }
        return Optional.ofNullable(best);
    }

    /** Throws (HTTP 409) when {@code env} is frozen. */
    public void require(String env) {
        active(env).ifPresent(f -> {
            throw new IllegalStateException(f.describe() + ". An AIO admin can override the freeze for one approval.");
        });
    }

    /** All configured windows with whether each is in force now, for the UI. */
    public List<Map<String, Object>> describe() {
        ZonedDateTime now = clock.instant().atZone(zone);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Window w : windows) {
            ZonedDateTime end = w.when().endIfCovers(now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("envs", w.envs());
            m.put("when", w.when().describe() + " (" + zone + ")");
            m.put("reason", w.reason());
            m.put("active", end != null);
            m.put("until", end == null ? null : end.toInstant());
            out.add(m);
        }
        return out;
    }

    static List<Window> parse(String spec, ZoneId zone) {
        List<Window> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) return out;
        for (String raw : spec.split(";")) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            try {
                String reason = null;
                int hash = entry.indexOf('#');
                if (hash >= 0) {
                    reason = entry.substring(hash + 1).trim();
                    entry = entry.substring(0, hash).trim();
                }
                int at = entry.indexOf('@');
                if (at <= 0) throw new IllegalArgumentException("expected <envs>@<when>");
                Set<String> envs = Set.of(entry.substring(0, at).trim().toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
                String[] range = entry.substring(at + 1).trim().split("\\s*/\\s*");
                if (range.length != 2) throw new IllegalArgumentException("expected <from>/<to>");
                When when;
                if (range[0].matches("\\d{4}-.*")) {   // absolute date-time (weekday names like TUE contain a T too)
                    ZonedDateTime from = LocalDateTime.parse(range[0]).atZone(zone), to = LocalDateTime.parse(range[1]).atZone(zone);
                    if (!to.isAfter(from)) throw new IllegalArgumentException("end must be after start");
                    when = new Absolute(from, to);
                } else {
                    String[] a = range[0].split("\\s+"), b = range[1].split("\\s+");
                    if (a.length != 2 || b.length != 2) throw new IllegalArgumentException("weekly range is like FRI 18:00/MON 09:00");
                    when = new Weekly(day(a[0]), LocalTime.parse(a[1]), day(b[0]), LocalTime.parse(b[1]));
                }
                out.add(new Window(envs, when, reason == null || reason.isEmpty() ? null : reason));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid AIO_FREEZE_WINDOWS entry '" + raw.trim() + "': " + e.getMessage(), e);
            }
        }
        return List.copyOf(out);
    }

    private static DayOfWeek day(String s) {
        String d = s.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek x : DayOfWeek.values()) if (x.name().startsWith(d) && d.length() >= 3) return x;
        throw new IllegalArgumentException("unknown day " + s);
    }
}
