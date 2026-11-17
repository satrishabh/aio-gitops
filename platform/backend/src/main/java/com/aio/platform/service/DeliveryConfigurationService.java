package com.aio.platform.service;

import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import org.springframework.stereotype.Service;

@Service
public class DeliveryConfigurationService {
    private final GitHubClient github;
    private final RegistryConnections registries;
    private final ProjectRepo projects;
    public DeliveryConfigurationService(GitHubClient github, RegistryConnections registries, ProjectRepo projects) {
        this.github = github; this.registries = registries; this.projects = projects;
    }
    public ProjectDeliveryConfig approve(Project project) {
        String sha = github.resolveCommit(project.repoOwner, project.repoName, project.defaultBranch);
        ProjectDeliveryConfig config = read(project, sha);
        checkConnections(project, config);
        project.deliveryConfigHash = config.hash();
        project.registryPushConnection = config.pushConnection();
        project.registryPullConnection = config.pullConnection();
        project.requestedArtifactTier = config.requestedTier();
        projects.save(project);
        return config;
    }
    public record Prepared(String commit, ProjectDeliveryConfig config) {}
    public Prepared prepare(Project project, String ref, String trigger, boolean deploy) {
        String sha = github.resolveCommit(project.repoOwner, project.repoName, ref);
        ProjectDeliveryConfig config = read(project, sha);
        if (!config.hash().equals(project.deliveryConfigHash))
            throw new IllegalArgumentException("Source delivery configuration changed; review and approve the default-branch configuration first");
        if ("external".equals(config.mode())) {
            if ("PR".equals(trigger)) throw new IllegalArgumentException("Pull-request builds cannot use private registry connections");
            if (deploy) throw new IllegalArgumentException("External registry builds currently require build-only mode; workload pull provisioning is pending");
            checkConnections(project, config);
        }
        return new Prepared(sha, config);
    }
    private ProjectDeliveryConfig read(Project project, String sha) {
        var file = github.getFile(project.repoOwner, project.repoName, ".aio/project.yaml", sha);
        if (file == null) throw new IllegalArgumentException("Commit .aio/project.yaml before enabling repository delivery configuration");
        return ProjectDeliveryConfig.parse(file.content());
    }
    private void checkConnections(Project project, ProjectDeliveryConfig config) {
        if ("external".equals(config.mode())) {
            registries.require(project, config.pushConnection(), config.host(), config.repository(), "push");
            registries.require(project, config.pullConnection(), config.host(), config.repository(), "pull");
        }
    }
}
