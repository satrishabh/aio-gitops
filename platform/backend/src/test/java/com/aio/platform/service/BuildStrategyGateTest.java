package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.PlatformSwitch;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.PlatformSwitchRepo;
import com.aio.platform.repo.ProjectRepo;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The buildah build strategy is held off with the podman switch (Podman / Buildah, phase 6). */
class BuildStrategyGateTest {
    private final Map<String, Boolean> stored = new HashMap<>();
    private PlatformSwitches switches;

    @BeforeEach
    void setUp() {
        PlatformSwitchRepo repo = mock(PlatformSwitchRepo.class);
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0, String.class))).map(on -> {
            PlatformSwitch s = new PlatformSwitch();
            s.name = i.getArgument(0);
            s.enabled = on;
            return s;
        }));
        when(repo.save(any())).thenAnswer(i -> {
            PlatformSwitch s = i.getArgument(0);
            stored.put(s.name, s.enabled);
            return s;
        });
        switches = new PlatformSwitches(repo);
    }

    @Test
    void podmanIsASwitchWithoutPipelineParameter() {
        assertTrue(PlatformSwitches.PIPELINE_STEPS.contains(PlatformSwitches.PODMAN));
        assertFalse(PlatformSwitches.PIPELINE_PARAMS.containsKey(PlatformSwitches.PODMAN));
        switches.set(PlatformSwitches.PODMAN, true, "alice");
        assertTrue(switches.on(PlatformSwitches.PODMAN));
        assertEquals(Map.of(), switches.pipelineParams());          // gates the strategy, passes nothing
        switches.set(PlatformSwitches.BIOME, true, "alice");
        assertEquals(Map.of("lint", "auto"), switches.pipelineParams());
    }

    @Test
    void buildahOnlyWhileTheSwitchIsOn() {
        assertEquals("auto", BuildStrategies.validate(null, switches));
        assertEquals("auto", BuildStrategies.validate(" ", switches));
        assertEquals("buildpacks", BuildStrategies.validate(" BuildPacks ", switches));
        assertEquals("dockerfile", BuildStrategies.validate("dockerfile", null));
        assertThrows(IllegalArgumentException.class, () -> BuildStrategies.validate("kaniko", switches));

        var off = assertThrows(IllegalArgumentException.class, () -> BuildStrategies.validate("buildah", switches));
        assertTrue(off.getMessage().contains("Podman / Buildah"), off.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BuildStrategies.validate("buildah", null));   // not wired = off
        assertEquals(Map.of("auto", true, "dockerfile", true, "buildpacks", true, "buildah", false), BuildStrategies.choices(switches));

        switches.set(PlatformSwitches.PODMAN, true, "alice");
        assertEquals("buildah", BuildStrategies.validate("buildah", switches));
        assertEquals(true, BuildStrategies.choices(switches).get("buildah"));
    }

    @Test
    void pipelineRunGetsTheStrategyOnlyWhenNotAutoAndRefusesBuildahWhileOff() {
        Project p = new Project();
        assertNull(TektonService.buildStrategy(p, switches));          // auto: pipeline default, nothing passed
        p.buildStrategy = "buildpacks";
        assertEquals("buildpacks", TektonService.buildStrategy(p, switches));

        p.buildStrategy = "buildah";                                     // chosen while on, switched off since
        var e = assertThrows(IllegalArgumentException.class, () -> TektonService.buildStrategy(p, switches));
        assertTrue(e.getMessage().contains("off until an admin switches on"), e.getMessage());
        switches.set(PlatformSwitches.PODMAN, true, "alice");
        assertEquals("buildah", TektonService.buildStrategy(p, switches));
    }

    @Test
    void createPipelineRunRefusesBuildahBeforeCallingKubernetes() {
        var k8s = mock(KubernetesClient.class);
        var props = mock(AppProperties.class);
        when(props.tekton()).thenReturn(new AppProperties.Tekton(
                "ci", "ci-pipeline", "pipeline-runner", "2Gi", "registry", "github-token", "15m", ""));
        when(props.gitops()).thenReturn(new AppProperties.GitOps(
                "https://github.com/team/gitops.git", "team", "gitops", "main", "apps", "helm/app"));
        var tekton = new TektonService(k8s, props);
        tekton.switches = switches;
        Project p = new Project();
        p.name = "example";
        p.buildStrategy = "buildah";
        assertThrows(IllegalArgumentException.class, () -> tekton.createPipelineRun(p, new CiRun()));
        verifyNoInteractions(k8s);
    }

    @Test
    void settingsAcceptBuildahOnlyWhileOnButKeepAnExistingChoice() {
        ProjectRepo projects = mock(ProjectRepo.class);
        when(projects.save(any())).thenAnswer(i -> i.getArgument(0));
        AuditService audit = mock(AuditService.class);
        ProjectService service = new ProjectService(projects, mock(GitHubClient.class), mock(GitOpsService.class),
                mock(ArgoCdService.class), mock(ClusterService.class), audit, mock(AppProperties.class),
                mock(DomainService.class), mock(RunService.class), mock(RegistryConnections.class));
        service.switches = switches;
        Project p = new Project();
        p.name = "shop";

        var e = assertThrows(IllegalArgumentException.class, () -> service.updateSettings(p, settings("buildah"), "bob"));
        assertTrue(e.getMessage().contains("Podman / Buildah"), e.getMessage());
        assertEquals("auto", p.buildStrategy);

        switches.set(PlatformSwitches.PODMAN, true, "alice");
        service.updateSettings(p, settings("buildah"), "bob");
        assertEquals("buildah", p.buildStrategy);
        verify(audit).record("bob", "project.settings", "shop", "buildStrategy=buildah");

        // Switched off again: saving the unchanged choice (with other settings) still works; runs are refused instead
        switches.set(PlatformSwitches.PODMAN, false, "alice");
        service.updateSettings(p, settings("buildah"), "bob");
        assertEquals("buildah", p.buildStrategy);
        service.updateSettings(p, settings("dockerfile"), "bob");
        assertEquals("dockerfile", p.buildStrategy);
    }

    private static ProjectService.Settings settings(String strategy) {
        return new ProjectService.Settings(null, null, null, null, null, null, null, null, null, null, null, null, strategy);
    }
}
