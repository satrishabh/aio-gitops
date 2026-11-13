package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import org.junit.jupiter.api.Test;

class DeliveryConfigurationServiceTest {
    private static final String CONFIG = """
            apiVersion: aio.platform/v1alpha1
            kind: ProjectDelivery
            spec:
              registry:
                mode: managed
            """;
    @Test void configurationIsReadFromResolvedImmutableCommitAndMustBeApproved() {
        var github = mock(GitHubClient.class); var registry = mock(RegistryConnections.class);
        var service = new DeliveryConfigurationService(github, registry, mock(ProjectRepo.class));
        Project p = new Project(); p.repoOwner = "team"; p.repoName = "app";
        p.deliveryConfigHash = ProjectDeliveryConfig.parse(CONFIG).hash();
        String commit = "a".repeat(40);
        when(github.resolveCommit("team", "app", "main")).thenReturn(commit);
        when(github.getFile("team", "app", ".aio/project.yaml", commit)).thenReturn(new GitHubClient.FileContent(CONFIG, "blob"));
        assertEquals(commit, service.prepare(p, "main", "MANUAL", true).commit());
        p.deliveryConfigHash = "unapproved";
        assertThrows(IllegalArgumentException.class, () -> service.prepare(p, "main", "MANUAL", true));
        verifyNoInteractions(registry);
    }
}
