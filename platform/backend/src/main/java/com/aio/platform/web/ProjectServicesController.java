package com.aio.platform.web;

import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.service.ProjectServicesService;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/**
 * Project -> Services: connectors a project uses per environment. Access is the project's (owner or admin, via the
 * ownership interceptor); anyone who may change the project's settings may attach and remove. Shapes: "API shapes"
 * in docs/architecture/PROJECT-SERVICES.md.
 */
@RestController
@RequestMapping("/api/projects/{id}/services")
public class ProjectServicesController {
    private final ProjectRepo projects;
    private final ProjectServicesService services;

    public ProjectServicesController(ProjectRepo projects, ProjectServicesService services) {
        this.projects = projects;
        this.services = services;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable Long id) {
        return services.overview(project(id));
    }

    @PostMapping
    public Map<String, Object> attach(@PathVariable Long id, @RequestBody ProjectServicesService.AttachRequest req,
                                      @AuthenticationPrincipal OAuth2User user) {
        return services.attach(project(id), req, Me.login(user));
    }

    @DeleteMapping("/{connector}")
    public ProjectServicesService.DetachResult detach(@PathVariable Long id, @PathVariable String connector,
                                                      @RequestParam String env,
                                                      @RequestParam(required = false) String confirm,
                                                      @AuthenticationPrincipal OAuth2User user) {
        return services.detach(project(id), connector, env, confirm, Me.login(user));
    }

    private Project project(Long id) {
        return projects.findById(id).orElseThrow();
    }
}
