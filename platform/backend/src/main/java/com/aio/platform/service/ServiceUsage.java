package com.aio.platform.service;

import com.aio.platform.domain.AttachedService;
import com.aio.platform.repo.AttachedServiceRepo;
import com.aio.platform.repo.ProjectRepo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Which projects (and environments) use a connector through Project -> Services (attached rows only). */
@Component
public class ServiceUsage {
    private final AttachedServiceRepo attached;
    private final ProjectRepo projects;

    public ServiceUsage(AttachedServiceRepo attached, ProjectRepo projects) {
        this.attached = attached;
        this.projects = projects;
    }

    /** The environments (was GitOpsService.ENVS); replaced by Spring, defaults to dev/staging/prod in unit tests. */
    @org.springframework.beans.factory.annotation.Autowired
    EnvironmentRegistry environments = EnvironmentRegistry.defaults();

    /** One project using the connector, with its environments in dev, staging, prod order. */
    public record Use(Long projectId, String project, List<String> envs) {}

    public List<Use> of(String connectorId) {
        Map<Long, List<String>> byProject = new LinkedHashMap<>();
        for (AttachedService s : attached.findByConnectorIdAndStatusOrderByProjectIdAscEnvAsc(connectorId, AttachedService.ATTACHED))
            byProject.computeIfAbsent(s.projectId, k -> new ArrayList<>()).add(s.env);
        List<Use> out = new ArrayList<>();
        byProject.forEach((id, envs) -> out.add(new Use(id, projects.findById(id).map(p -> p.name).orElse("#" + id),
                environments.names().stream().filter(envs::contains).toList())));
        out.sort(java.util.Comparator.comparing(Use::project));
        return out;
    }

    /** "shop (dev, staging), blog (dev)" for refusals and audit lines. */
    static String describe(List<Use> uses) {
        return uses.stream().map(u -> u.project() + " (" + String.join(", ", u.envs()) + ")").collect(Collectors.joining(", "));
    }
}
