package com.aio.platform.service;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Deployment;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.DeploymentRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * DORA-style delivery metrics computed from data the platform already records.
 * <ul>
 *   <li><b>Production</b> is every prod-like environment ({@code environments.prod_like}: prod, plus e.g. an
 *       environment after prod on another cloud); with only prod the numbers are exactly as before.</li>
 *   <li><b>Deploy frequency</b>: Argo CD deployments per day (prod and dev), placeholder deploys excluded.
 *       Staging deployments are counted too, for the Overview's pipeline strip.</li>
 *   <li><b>Commits built</b>: distinct commit SHAs that CI ran on in the period (not every pushed commit).</li>
 *   <li><b>Lead time</b>: CI run start -> prod deployment of the image that run built (median, hours).</li>
 *   <li><b>Change failure rate</b>: completed prod rollbacks / prod deployments.</li>
 *   <li><b>Time to restore</b>: first failing deploy-eligible CI run -> next green one (mean, hours).
 *       This is pipeline recovery time; the platform has no incident data for production MTTR.</li>
 * </ul>
 */
@Service
public class MetricsService {
    private final ProjectRepo projects;
    private final CiRunRepo runs;
    private final DeploymentRepo deployments;
    private final PromotionRepo promotions;
    /** Which envs count as production; absent (tests) = prod only. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    EnvironmentRegistry environments;

    public MetricsService(ProjectRepo projects, CiRunRepo runs, DeploymentRepo deployments, PromotionRepo promotions) {
        this.projects = projects;
        this.runs = runs;
        this.deployments = deployments;
        this.promotions = promotions;
    }

    public record Dora(int days, int prodDeploys, int devDeploys, double prodDeploysPerDay, double devDeploysPerDay,
                       Double leadTimeHoursMedian, Double changeFailureRate, Double timeToRestoreHoursMean,
                       int ciRuns, Double ciSuccessRate, Double ciDurationMinutesMean, int prodRollbacks,
                       int stagingDeploys, int commitsBuilt, Map<String, Integer> deploysByEnv) {}

    static final Set<String> PROD_ONLY = Set.of("prod");

    private Set<String> prodLike() {
        return environments == null ? PROD_ONLY : environments.prodLike();
    }

    public Map<String, Object> overview(int days) {
        int d = Math.max(1, Math.min(days, 365));
        Instant since = Instant.now().minus(Duration.ofDays(d));
        var visibleProjects = com.aio.platform.security.UserScope.projects(projects);
        var ids = visibleProjects.stream().map(p -> p.id).toList();
        boolean admin = com.aio.platform.security.UserScope.admin();
        List<CiRun> allRuns = admin ? runs.findByCreatedAtAfterOrderByIdAsc(since)
                : runs.findByProjectIdInAndCreatedAtAfterOrderByIdAsc(ids, since);
        List<Deployment> allDeps = admin ? deployments.findByDeployedAtAfter(since)
                : deployments.findByProjectIdInAndDeployedAtAfter(ids, since);
        var page = org.springframework.data.domain.PageRequest.of(0, 5000);
        List<Promotion> allPromos = (admin ? promotions.findAllByOrderByIdDesc(page) : promotions.findVisible(ids, null, page))
                .stream().filter(p -> p.requestedAt != null && p.requestedAt.isAfter(since)).toList();

        Set<String> prod = prodLike();
        Map<String, Object> perProject = new LinkedHashMap<>();
        for (Project p : visibleProjects) {
            perProject.put(p.name, compute(d,
                    allDeps.stream().filter(x -> p.id.equals(x.projectId)).toList(),
                    allRuns.stream().filter(x -> p.id.equals(x.projectId)).toList(),
                    allPromos.stream().filter(x -> p.id.equals(x.projectId)).toList(), prod));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("prodLike", prod);
        out.put("overall", compute(d, allDeps, allRuns, allPromos, prod));
        out.put("projects", perProject);
        out.put("daily", daily(d, allRuns, allDeps, allPromos, prod));
        return out;
    }

    /** A measurement (hours) that happened at a point in time: one prod lead time, or one pipeline recovery. */
    record Timed(Instant at, double hours) {}

    static List<Map<String, Object>> daily(int days, List<CiRun> ciRuns, List<Deployment> deps) {
        return daily(days, ciRuns, deps, List.of());
    }

    /**
     * Per-day CI outcomes and deployments for charts (UTC days, oldest first, zero-filled), with deploys split per
     * environment, plus the day's DORA readings for sparklines: median lead time of that day's prod deploys,
     * completed prod rollbacks, and mean time to restore of recoveries that finished that day (null when none).
     */
    static List<Map<String, Object>> daily(int days, List<CiRun> ciRuns, List<Deployment> deps, List<Promotion> promos) {
        return daily(days, ciRuns, deps, promos, PROD_ONLY);
    }

    static List<Map<String, Object>> daily(int days, List<CiRun> ciRuns, List<Deployment> deps, List<Promotion> promos, Set<String> prodLike) {
        List<Deployment> prod = deps.stream().filter(x -> x.deployedAt != null && prodLike.contains(x.environment)
                && !GitOpsService.PLACEHOLDER_TAG.equals(x.imageTag)).toList();
        List<Timed> leads = leadTimes(prod, ciRuns);
        List<Timed> restores = restores(ciRuns);
        List<Promotion> rollbacks = promos.stream().filter(p -> isProdRollback(p, prodLike)).toList();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            java.time.LocalDate day = today.minusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("succeeded", ciRuns.stream().filter(r -> CiRun.SUCCEEDED.equals(r.status) && day(r.createdAt).equals(day)).count());
            row.put("failed", ciRuns.stream().filter(r -> CiRun.FAILED.equals(r.status) && day(r.createdAt).equals(day)).count());
            List<Deployment> that = deps.stream().filter(x -> x.deployedAt != null && !GitOpsService.PLACEHOLDER_TAG.equals(x.imageTag)
                    && day(x.deployedAt).equals(day)).toList();
            row.put("deploys", (long) that.size());
            row.put("deploysDev", that.stream().filter(x -> "dev".equals(x.environment)).count());
            row.put("deploysStaging", that.stream().filter(x -> "staging".equals(x.environment)).count());
            row.put("deploysProd", that.stream().filter(x -> prodLike.contains(x.environment)).count());
            row.put("deploysByEnv", byEnv(that));
            row.put("leadTimeHours", median(leads.stream().filter(t -> day(t.at()).equals(day)).map(Timed::hours).toList()));
            row.put("rollbacks", rollbacks.stream().filter(p -> {
                Instant at = p.decidedAt != null ? p.decidedAt : p.requestedAt;
                return at != null && day(at).equals(day);
            }).count());
            row.put("restoreHours", mean(restores.stream().filter(t -> day(t.at()).equals(day)).map(Timed::hours).toList()));
            out.add(row);
        }
        return out;
    }

    private static java.time.LocalDate day(Instant t) {
        return t.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    /** Deployments per environment name, in first-seen order. */
    static Map<String, Integer> byEnv(List<Deployment> deps) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Deployment x : deps) if (x.environment != null) m.merge(x.environment, 1, Integer::sum);
        return m;
    }

    static Dora compute(int days, List<Deployment> deps, List<CiRun> ciRuns, List<Promotion> promos) {
        return compute(days, deps, ciRuns, promos, PROD_ONLY);
    }

    static Dora compute(int days, List<Deployment> deps, List<CiRun> ciRuns, List<Promotion> promos, Set<String> prodLike) {
        List<Deployment> real = deps.stream()
                .filter(x -> x.deployedAt != null && !GitOpsService.PLACEHOLDER_TAG.equals(x.imageTag)).toList();
        List<Deployment> prod = real.stream().filter(x -> prodLike.contains(x.environment)).toList();
        int dev = (int) real.stream().filter(x -> "dev".equals(x.environment)).count();
        int staging = (int) real.stream().filter(x -> "staging".equals(x.environment)).count();
        int commits = (int) ciRuns.stream().map(r -> r.commitSha).filter(Objects::nonNull).filter(sha -> !sha.isBlank()).distinct().count();

        List<Double> leads = leadTimes(prod, ciRuns).stream().map(Timed::hours).toList();
        int rollbacks = (int) promos.stream().filter(p -> isProdRollback(p, prodLike)).count();
        List<Double> restores = restores(ciRuns).stream().map(Timed::hours).toList();

        List<CiRun> finished = ciRuns.stream()
                .filter(r -> CiRun.SUCCEEDED.equals(r.status) || CiRun.FAILED.equals(r.status)).toList();
        long ok = finished.stream().filter(r -> CiRun.SUCCEEDED.equals(r.status)).count();
        List<Double> durations = finished.stream().filter(r -> r.startedAt != null && r.finishedAt != null)
                .map(r -> Duration.between(r.startedAt, r.finishedAt).toSeconds() / 60.0).toList();

        return new Dora(days, prod.size(), dev,
                round(prod.size() / (double) days), round(dev / (double) days),
                median(leads),
                prod.isEmpty() ? null : round(rollbacks / (double) prod.size()),
                mean(restores),
                ciRuns.size(),
                finished.isEmpty() ? null : round(ok / (double) finished.size()),
                mean(durations),
                rollbacks,
                staging,
                commits,
                byEnv(real));
    }

    private static boolean isProdRollback(Promotion p, Set<String> prodLike) {
        return "ROLLBACK".equals(p.kind) && prodLike.contains(p.toEnv) && Promotion.DONE.equals(p.status);
    }

    /** Lead time per prod deploy: the successful run that built image ...:<tag> -> the deploy, timed at the deploy. */
    static List<Timed> leadTimes(List<Deployment> prod, List<CiRun> ciRuns) {
        List<Timed> out = new ArrayList<>();
        for (Deployment pd : prod) {
            if (pd.imageTag == null) continue;
            ciRuns.stream()
                    .filter(r -> CiRun.SUCCEEDED.equals(r.status) && r.imageUrl != null && r.imageUrl.endsWith(":" + pd.imageTag)
                            && Objects.equals(r.projectId, pd.projectId) && r.createdAt.isBefore(pd.deployedAt))
                    .min(Comparator.comparing(r -> r.createdAt))
                    .ifPresent(r -> out.add(new Timed(pd.deployedAt, hours(r.createdAt, pd.deployedAt))));
        }
        return out;
    }

    /** Pipeline recoveries per project: failing deploy-eligible run -> next succeeded one, timed when the green run ended. */
    static List<Timed> restores(List<CiRun> ciRuns) {
        List<Timed> out = new ArrayList<>();
        Map<Long, List<CiRun>> byProject = ciRuns.stream().filter(r -> r.deploy)
                .collect(Collectors.groupingBy(r -> r.projectId));
        for (List<CiRun> list : byProject.values()) {
            Instant brokenSince = null;
            for (CiRun r : list.stream().sorted(Comparator.comparing(r -> r.createdAt)).toList()) {
                if (CiRun.FAILED.equals(r.status) && brokenSince == null) brokenSince = end(r);
                else if (CiRun.SUCCEEDED.equals(r.status) && brokenSince != null) {
                    out.add(new Timed(end(r), hours(brokenSince, end(r))));
                    brokenSince = null;
                }
            }
        }
        return out;
    }

    private static Instant end(CiRun r) {
        return r.finishedAt != null ? r.finishedAt : r.createdAt;
    }

    private static double hours(Instant a, Instant b) {
        return Duration.between(a, b).toSeconds() / 3600.0;
    }

    static Double median(List<Double> xs) {
        if (xs.isEmpty()) return null;
        List<Double> s = xs.stream().sorted().toList();
        int n = s.size();
        return round(n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2);
    }

    static Double mean(List<Double> xs) {
        return xs.isEmpty() ? null : round(xs.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
