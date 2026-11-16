package com.aio.platform.assistant;

import com.aio.platform.domain.AssistantReport;
import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.AssistantReportRepo;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * G6 first scheduled task: a short daily summary of the last 24 hours of builds and deployments, for the platform team.
 *
 * <p>Built for a platform that sleeps (AKS stops at night, Sablier scales AIO to zero when idle): instead of a fixed
 * cron that is missed while asleep, it checks every 30 minutes and writes today's summary the first time AIO is
 * awake after {@code hour}. One report per day (unique in the database), so restarts never spend quota twice.
 * Quiet days cost nothing: with no activity it stores a one-line note without calling the model. Model calls run in
 * the limiter's background lane, so they wait for free slots and never use people's share of the free quota.
 * It never wakes the platform and holds no keep-awake lease. Off by default ({@code AIO_ASSISTANT_DIGEST_ENABLED}).
 */
@Component
public class DailyDigestJob {
    static final String KIND = "daily-digest";
    static final String QUIET = "No builds or deployments in the last 24 hours.";
    static final String TASK = """
            Task: a morning summary of the last 24 hours of builds (CI runs) and deployments (promotions).
            Write 3 to 6 short bullet points, most important first: deployments waiting for a decision or paused,
            failures (group repeats of the same project or message), then what went well in one bullet. Name projects.
            """;
    private static final Set<String> FAILED = Set.of("FAILED");
    private static final Set<String> NEEDS_DECISION = Set.of("PENDING", "PAUSED");
    private static final Logger log = LoggerFactory.getLogger(DailyDigestJob.class);

    private final AssistantService assistant;
    private final CiRunRepo runs;
    private final PromotionRepo promotions;
    private final ProjectRepo projects;
    private final AssistantReportRepo reports;
    private final Clock clock;
    private final boolean enabled;
    private final int hour;
    private final ZoneId zone;
    private final Duration maxWait;
    private LocalDate attemptsDay;
    private int attempts;

    public DailyDigestJob(AssistantService assistant, CiRunRepo runs, PromotionRepo promotions, ProjectRepo projects,
                          AssistantReportRepo reports, ObjectProvider<Clock> clock,
                          @Value("${app.assistant.digest.enabled:false}") boolean enabled,
                          @Value("${app.assistant.digest.hour:9}") int hour,
                          @Value("${app.assistant.digest.zone:Asia/Kolkata}") String zone,
                          @Value("${app.assistant.digest.max-wait:PT10M}") Duration maxWait) {
        this.assistant = assistant;
        this.runs = runs;
        this.promotions = promotions;
        this.projects = projects;
        this.reports = reports;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.enabled = enabled;
        this.hour = hour;
        this.zone = ZoneId.of(zone);
        this.maxWait = maxWait;
    }

    @Scheduled(fixedDelayString = "${app.assistant.digest.check-ms:1800000}",
               initialDelayString = "${app.assistant.digest.initial-ms:300000}")
    public void tick() {
        if (!enabled || !assistant.enabled()) return;
        try {
            runIfDue();
        } catch (RuntimeException e) {
            log.warn("daily assistant summary not written: {}", e.getMessage());
        }
    }

    /** Writes today's report when it is due and missing. Returns what was stored, or null. */
    AssistantReport runIfDue() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        LocalDate today = now.toLocalDate();
        if (now.getHour() < hour || reports.existsByKindAndDay(KIND, today)) return null;
        if (!today.equals(attemptsDay)) {
            attemptsDay = today;
            attempts = 0;
        }
        if (attempts >= 3) return null;   // a provider that keeps failing must not drain the daily quota
        attempts++;

        Instant since = clock.instant().minus(Duration.ofHours(24));
        List<CiRun> recentRuns = runs.findByCreatedAtAfterOrderByIdAsc(since);
        List<Promotion> recentPromotions = promotions.findByRequestedAtAfterOrderByIdAsc(since);
        Set<Long> ids = new java.util.HashSet<>();
        recentRuns.forEach(r -> ids.add(r.projectId));
        recentPromotions.forEach(p -> ids.add(p.projectId));
        Map<Long, String> names = projects.findAllById(ids).stream()
                .collect(Collectors.toMap(p -> p.id, (Project p) -> p.name, (a, b) -> a));

        AssistantReport r = new AssistantReport();
        r.kind = KIND;
        r.day = today;
        r.createdAt = clock.instant();
        r.basis = "runs=" + recentRuns.size() + ", deployments=" + recentPromotions.size();
        if (recentRuns.isEmpty() && recentPromotions.isEmpty()) {
            r.model = "none";
            r.text = QUIET;   // nothing to summarise: no model call, no quota spent
        } else {
            String facts = facts(recentRuns, recentPromotions, id -> names.getOrDefault(id, "project " + id));
            AssistantService.Answer a = assistant.summarize("aio-scheduler", TASK, "Builds and deployments, last 24 hours", facts, maxWait);
            r.model = a.model();
            r.text = a.answer();
        }
        try {
            return reports.save(r);
        } catch (DataIntegrityViolationException e) {
            return null;   // another replica or a restart wrote today's report first
        }
    }

    /** Counts plus the items that need attention; messages clipped. Names and messages are redacted by the service. */
    static String facts(List<CiRun> runs, List<Promotion> promotions, Function<Long, String> name) {
        StringBuilder f = new StringBuilder();
        f.append("CI runs: ").append(runs.size()).append(" ").append(countBy(runs.stream().map(r -> r.status).toList())).append('\n');
        runs.stream().filter(r -> FAILED.contains(r.status) || "FAILED".equals(r.releaseStatus)).limit(15).forEach(r ->
                f.append("- failed run #").append(r.id).append(" ").append(name.apply(r.projectId))
                        .append(" (").append(r.trigger).append(", ").append(r.status)
                        .append(r.releaseStatus == null ? "" : ", release " + r.releaseStatus).append("): ")
                        .append(AssistantService.clip(oneLine(r.releaseMessage != null && "FAILED".equals(r.releaseStatus) ? r.releaseMessage : r.message), 200))
                        .append('\n'));
        f.append("Deployments: ").append(promotions.size()).append(" ").append(countBy(promotions.stream().map(p -> p.status).toList())).append('\n');
        promotions.stream().filter(p -> FAILED.contains(p.status) || NEEDS_DECISION.contains(p.status))
                .limit(15).forEach(p ->
                f.append("- deployment #").append(p.id).append(" ").append(name.apply(p.projectId)).append(" ")
                        .append(p.fromEnv).append(" to ").append(p.toEnv).append(" (").append(p.kind).append(", ").append(p.status)
                        .append(", requested by ").append(p.requestedBy).append("): ")
                        .append(AssistantService.clip(oneLine(p.message), 200)).append('\n'));
        return f.toString();
    }

    private static String countBy(Collection<String> statuses) {
        Map<String, Long> by = new TreeMap<>(statuses.stream().collect(Collectors.groupingBy(s -> s == null ? "UNKNOWN" : s, Collectors.counting())));
        return by.isEmpty() ? "" : by.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}
