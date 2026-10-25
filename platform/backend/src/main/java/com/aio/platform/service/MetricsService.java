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
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * DORA-style delivery metrics computed from data the platform already records.
 * <ul>
 *   <li><b>Deploy frequency</b>: Argo CD deployments per day (prod and dev), placeholder deploys excluded.</li>
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

    public MetricsService(ProjectRepo projects, CiRunRepo runs, DeploymentRepo deployments, PromotionRepo promotions) {
        this.projects = projects;
        this.runs = runs;
        this.deployments = deployments;
        this.promotions = promotions;
    }

    public record Dora(int days, int prodDeploys, int devDeploys, double prodDeploysPerDay, double devDeploysPerDay,
                       Double leadTimeHoursMedian, Double changeFailureRate, Double timeToRestoreHoursMean,
                       int ciRuns, Double ciSuccessRate, Double ciDurationMinutesMean, int prodRollbacks) {}

    public Map<String, Object> overview(int days) {
        int d = Math.max(1, Math.min(days, 365));
        Instant since = Instant.now().minus(Duration.ofDays(d));
        List<CiRun> allRuns = runs.findByCreatedAtAfterOrderByIdAsc(since);
        List<Deployment> allDeps = deployments.findByDeployedAtAfter(since);
        List<Promotion> allPromos = promotions.findAllByOrderByIdDesc(org.springframework.data.domain.PageRequest.of(0, 5000))
                .stream().filter(p -> p.requestedAt != null && p.requestedAt.isAfter(since)).toList();

        Map<String, Object> perProject = new LinkedHashMap<>();
        for (Project p : projects.findAll(org.springframework.data.domain.Sort.by("name"))) {
            perProject.put(p.name, compute(d,
                    allDeps.stream().filter(x -> p.id.equals(x.projectId)).toList(),
                    allRuns.stream().filter(x -> p.id.equals(x.projectId)).toList(),
                    allPromos.stream().filter(x -> p.id.equals(x.projectId)).toList()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("overall", compute(d, allDeps, allRuns, allPromos));
        out.put("projects", perProject);
        return out;
    }

    static Dora compute(int days, List<Deployment> deps, List<CiRun> ciRuns, List<Promotion> promos) {
        List<Deployment> real = deps.stream()
                .filter(x -> x.deployedAt != null && !GitOpsService.PLACEHOLDER_TAG.equals(x.imageTag)).toList();
        List<Deployment> prod = real.stream().filter(x -> "prod".equals(x.environment)).toList();
        int dev = (int) real.stream().filter(x -> "dev".equals(x.environment)).count();

        // lead time: match prod deploy tag -> the successful run that produced image ...:<tag>
        List<Double> leads = new ArrayList<>();
        for (Deployment pd : prod) {
            if (pd.imageTag == null) continue;
            ciRuns.stream()
                    .filter(r -> CiRun.SUCCEEDED.equals(r.status) && r.imageUrl != null && r.imageUrl.endsWith(":" + pd.imageTag)
                            && Objects.equals(r.projectId, pd.projectId) && r.createdAt.isBefore(pd.deployedAt))
                    .min(Comparator.comparing(r -> r.createdAt))
                    .ifPresent(r -> leads.add(hours(r.createdAt, pd.deployedAt)));
        }

        int rollbacks = (int) promos.stream()
                .filter(p -> "ROLLBACK".equals(p.kind) && "prod".equals(p.toEnv) && Promotion.DONE.equals(p.status)).count();

        // time to restore: per project, failing deploy-eligible run -> next succeeded deploy-eligible run
        List<Double> restores = new ArrayList<>();
        Map<Long, List<CiRun>> byProject = ciRuns.stream().filter(r -> r.deploy)
                .collect(Collectors.groupingBy(r -> r.projectId));
        for (List<CiRun> list : byProject.values()) {
            Instant brokenSince = null;
            for (CiRun r : list.stream().sorted(Comparator.comparing(r -> r.createdAt)).toList()) {
                if (CiRun.FAILED.equals(r.status) && brokenSince == null) brokenSince = end(r);
                else if (CiRun.SUCCEEDED.equals(r.status) && brokenSince != null) {
                    restores.add(hours(brokenSince, end(r)));
                    brokenSince = null;
                }
            }
        }

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
                rollbacks);
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
