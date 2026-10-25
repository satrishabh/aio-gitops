package com.aio.platform.web;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.RunStep;
import com.aio.platform.repo.*;
import com.aio.platform.service.ClusterService;
import com.aio.platform.service.ProjectService;
import com.aio.platform.service.PromotionService;
import com.aio.platform.service.RunService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectRepo projects;
    private final CiRunRepo runs;
    private final RunStepRepo steps;
    private final EnvStatusRepo envs;
    private final DeploymentRepo deployments;
    private final PromotionRepo promotions;
    private final ProjectService projectService;
    private final RunService runService;
    private final PromotionService promotionService;
    private final ClusterService cluster;

    public ProjectController(ProjectRepo projects, CiRunRepo runs, RunStepRepo steps, EnvStatusRepo envs,
                             DeploymentRepo deployments, PromotionRepo promotions, ProjectService projectService,
                             RunService runService, PromotionService promotionService, ClusterService cluster) {
        this.projects = projects;
        this.runs = runs;
        this.steps = steps;
        this.envs = envs;
        this.deployments = deployments;
        this.promotions = promotions;
        this.projectService = projectService;
        this.runService = runService;
        this.promotionService = promotionService;
        this.cluster = cluster;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return projects.findAll(Sort.by("name")).stream().map(p -> Map.<String, Object>of(
                "project", p,
                "lastRun", runs.findFirstByProjectIdOrderByIdDesc(p.id).map(r -> (Object) r).orElse(Map.of()),
                "environments", envs.findByProjectId(p.id))).toList();
    }

    @PostMapping
    public Project create(@RequestBody ProjectService.CreateRequest req, @AuthenticationPrincipal OAuth2User user) {
        return projectService.create(req, Me.login(user));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Project p = projects.findById(id).orElseThrow();
        List<CiRun> recent = runs.findByProjectIdOrderByIdDesc(id, PageRequest.of(0, 30));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", p);
        out.put("environments", envs.findByProjectId(id));
        Map<String, String> namespaces = new LinkedHashMap<>();
        for (String env : com.aio.platform.service.GitOpsService.ENVS) namespaces.put(env, ClusterService.namespace(p, env));
        out.put("namespaces", namespaces);
        out.put("runs", recent);
        out.put("failedSteps", failedSteps(recent));
        out.put("deployments", deployments.findByProjectIdOrderByDeployedAtDesc(id, PageRequest.of(0, 60)));
        out.put("promotions", promotions.findByProjectIdOrderByIdDesc(id, PageRequest.of(0, 30)));
        return out;
    }

    /** runId -> "task / step" of the first failed step, so the project page can say *where* a run broke. */
    private Map<Long, String> failedSteps(List<CiRun> recent) {
        Map<Long, String> m = new LinkedHashMap<>();
        for (CiRun r : recent) {
            if (!CiRun.FAILED.equals(r.status)) continue;
            steps.findByRunIdOrderById(r.id).stream()
                    .filter(s -> "FAILED".equals(s.status)).findFirst()
                    .map(RunStep::label).ifPresent(l -> m.put(r.id, l));
        }
        return m;
    }

    public record SettingsRequest(String scanSeverity, String testImage, String testCommand) {}

    @PatchMapping("/{id}")
    public Project update(@PathVariable Long id, @RequestBody SettingsRequest req, @AuthenticationPrincipal OAuth2User user) {
        return projectService.updateSettings(projects.findById(id).orElseThrow(), req.scanSeverity(), req.testImage(),
                req.testCommand(), Me.login(user));
    }

    public record DeleteRequest(String confirmName) {}

    /** Deletes Argo CD apps (cascade), namespaces, GitOps files, webhook and history. Requires typing the name. */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id, @RequestBody DeleteRequest req,
                                      @AuthenticationPrincipal OAuth2User user) {
        Project p = projects.findById(id).orElseThrow();
        if (req == null || !p.name.equals(req.confirmName())) throw new IllegalArgumentException("type the project name to confirm");
        return Map.of("deleted", p.name, "warnings", projectService.delete(p, Me.login(user)));
    }

    /** Re-applies namespaces, pull secrets, values conventions and Argo CD apps for this project. */
    @PostMapping("/{id}/reconcile")
    public Map<String, String> reconcile(@PathVariable Long id) {
        Project p = projects.findById(id).orElseThrow();
        cluster.reconcile(p);
        return Map.of("status", "reconciled");
    }

    public record RunRequest(String ref) {}

    @PostMapping("/{id}/runs")
    public Object run(@PathVariable Long id, @RequestBody(required = false) RunRequest req,
                      @AuthenticationPrincipal OAuth2User user) {
        Project p = projects.findById(id).orElseThrow();
        return runService.trigger(p, req == null ? null : req.ref(), "MANUAL", Me.login(user), true);
    }

    public record PromoteRequest(String from, String to) {}

    @PostMapping("/{id}/promote")
    public Object promote(@PathVariable Long id, @RequestBody PromoteRequest req,
                          @AuthenticationPrincipal OAuth2User user) {
        Project p = projects.findById(id).orElseThrow();
        return promotionService.promote(p, req.from(), req.to(), Me.login(user));
    }

    public record RollbackRequest(Long deploymentId) {}

    @PostMapping("/{id}/environments/{env}/rollback")
    public Object rollback(@PathVariable Long id, @PathVariable String env, @RequestBody RollbackRequest req,
                           @AuthenticationPrincipal OAuth2User user) {
        Project p = projects.findById(id).orElseThrow();
        var d = deployments.findById(req.deploymentId()).orElseThrow();
        if (!d.projectId.equals(id) || !d.environment.equals(env)) throw new IllegalArgumentException("deployment mismatch");
        return promotionService.rollback(p, env, d, Me.login(user));
    }
}
