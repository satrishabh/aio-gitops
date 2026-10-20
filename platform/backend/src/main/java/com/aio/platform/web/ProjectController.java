package com.aio.platform.web;

import com.aio.platform.domain.Project;
import com.aio.platform.repo.*;
import com.aio.platform.service.ProjectService;
import com.aio.platform.service.PromotionService;
import com.aio.platform.service.RunService;
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
    private final EnvStatusRepo envs;
    private final DeploymentRepo deployments;
    private final PromotionRepo promotions;
    private final ProjectService projectService;
    private final RunService runService;
    private final PromotionService promotionService;

    public ProjectController(ProjectRepo projects, CiRunRepo runs, EnvStatusRepo envs, DeploymentRepo deployments,
                             PromotionRepo promotions, ProjectService projectService, RunService runService,
                             PromotionService promotionService) {
        this.projects = projects;
        this.runs = runs;
        this.envs = envs;
        this.deployments = deployments;
        this.promotions = promotions;
        this.projectService = projectService;
        this.runService = runService;
        this.promotionService = promotionService;
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
        return Map.of(
                "project", p,
                "environments", envs.findByProjectId(id),
                "runs", runs.findByProjectIdOrderByIdDesc(id, PageRequest.of(0, 30)),
                "deployments", deployments.findByProjectIdOrderByDeployedAtDesc(id, PageRequest.of(0, 60)),
                "promotions", promotions.findByProjectIdOrderByIdDesc(id, PageRequest.of(0, 30)));
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
