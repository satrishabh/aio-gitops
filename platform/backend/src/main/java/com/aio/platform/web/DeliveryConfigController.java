package com.aio.platform.web;

import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.security.UserScope;
import com.aio.platform.service.ProjectDeliveryConfig;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/delivery-config")
public class DeliveryConfigController {
    private final ProjectRepo projects;
    private final com.aio.platform.service.DeliveryConfigurationService configuration;
    private final com.aio.platform.service.RegistryConnections registries;
    public DeliveryConfigController(ProjectRepo projects, com.aio.platform.service.DeliveryConfigurationService configuration,
                                    com.aio.platform.service.RegistryConnections registries) {
        this.projects = projects; this.configuration = configuration; this.registries = registries;
    }
    public record ValidateRequest(String yaml) {}
    @PostMapping("/validate")
    public ProjectDeliveryConfig validate(@PathVariable Long id, @RequestBody ValidateRequest request) {
        UserScope.project(projects, id);
        return ProjectDeliveryConfig.parse(request.yaml());
    }
    @PostMapping
    public ProjectDeliveryConfig approve(@PathVariable Long id) {
        return configuration.approve(UserScope.project(projects, id));
    }
    @PostMapping("/connections")
    public com.aio.platform.service.RegistryConnections.Connection register(@PathVariable Long id,
            @RequestBody com.aio.platform.service.RegistryConnections.Registration registration) {
        return registries.register(UserScope.project(projects, id), registration);
    }
    @DeleteMapping("/connections/{name}")
    public void revoke(@PathVariable Long id, @PathVariable String name) {
        registries.revoke(UserScope.project(projects, id), name);
    }
}
