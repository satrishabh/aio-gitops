package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.PlatformSwitch;
import com.aio.platform.repo.PlatformSwitchRepo;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhaseSwitchTest {
    // Catalog order puts Harbor before what it needs, so the switch must reorder
    private static final String CATALOG = """
            connectors:
            - id: harbor
              name: Harbor
              category: Security
              kind: managed
              phase: '5'
              namespace: harbor
              releaseName: harbor
              chartRepo: https://helm.goharbor.io
              chart: harbor
              version: 1.19.4
              previousVersion: 1.19.2
              valuesPath: values/harbor/values.yaml
              requires: [cloudnative-pg]
            - id: cloudnative-pg
              name: CloudNativePG
              category: Data
              kind: managed
              phase: '5'
              namespace: cnpg-system
              releaseName: cnpg
              chartRepo: https://cloudnative-pg.github.io/charts
              chart: cloudnative-pg
              version: 0.29.1
              valuesPath: values/cloudnative-pg/values.yaml
            - id: biome
              name: Biome (CI step)
              category: CI/CD
              kind: core
              phase: '5'
              namespace: tekton-pipelines
              deployment: tekton-pipelines-controller
            - id: prometheus
              name: Prometheus
              category: Observability
              kind: managed
              phase: '1'
              namespace: monitoring
              releaseName: kps
              chartRepo: https://prometheus-community.github.io/helm-charts
              chart: kube-prometheus-stack
              version: 92.17.1
              valuesPath: values/kube-prometheus-stack/values.yaml
            - id: loki
              name: Loki
              category: Observability
              kind: managed
              phase: '4'
              namespace: loki
              releaseName: loki
              chartRepo: https://grafana.github.io/helm-charts
              chart: loki
              version: 7.5.0
              valuesPath: values/loki/values.yaml
              requires: [prometheus]
            - id: gatus
              name: Gatus
              category: Reliability
              kind: managed
              phase: '4'
              namespace: gatus
              releaseName: gatus
              chartRepo: https://twin.github.io/helm-charts
              chart: gatus
              version: 1.5.0
              valuesPath: values/gatus/values.yaml
            - id: velero
              name: Velero
              category: Reliability
              kind: planned
              phase: '5'
            """;

    @TempDir Path dir;
    private final Set<String> apps = new HashSet<>();          // Argo CD apps that exist
    private final Map<String, Boolean> stored = new java.util.HashMap<>();
    private ConnectorService service;
    private PlatformSwitches switches;
    private ArgoCdService argo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        Path file = dir.resolve("catalog.yaml");
        Files.writeString(file, CATALOG);
        argo = mock(ArgoCdService.class);
        when(argo.getApp(anyString(), eq(false))).thenAnswer(i -> apps.contains(i.getArgument(0, String.class)) ? Map.of() : null);
        doAnswer(i -> apps.add(J.str(i.getArgument(0, Map.class), "metadata", "name"))).when(argo).upsertApplication(any());
        doAnswer(i -> apps.remove(i.getArgument(0, String.class))).when(argo).deleteApp(anyString());

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

        AppProperties props = mock(AppProperties.class);
        when(props.gitops()).thenReturn(new AppProperties.GitOps(
                "https://github.com/example/gitops.git", "example", "gitops", "main", "apps", "helm/app"));
        service = new ConnectorService(new ConnectorCatalog(file.toString(), "aks"), argo, mock(KubernetesClient.class), props);
        service.switches = switches;
    }

    @Test
    void switchOnInstallsDependenciesFirstAndTurnsOnPipelineSteps() {
        var steps = service.switchPhase("5", true, "alice");
        assertEquals(List.of("cloudnative-pg", "harbor", "biome"), steps.stream().map(ConnectorService.PhaseStep::id).toList());
        assertTrue(steps.stream().allMatch(ConnectorService.PhaseStep::ok), steps.toString());
        assertEquals(Set.of("connector-cloudnative-pg", "connector-harbor"), apps);
        assertTrue(switches.on("biome"));
        assertEquals(Map.of("lint", "auto"), switches.pipelineParams());

        // Again: nothing changes, everything reports already on
        var again = service.switchPhase("5", true, "alice");
        assertTrue(again.stream().allMatch(s -> s.ok() && s.detail().startsWith("already on")), again.toString());
    }

    @Test
    void switchOffRemovesInReverseOrderAndHoldsPipelineStepsOff() {
        service.switchPhase("5", true, "alice");
        var steps = service.switchPhase("5", false, "bob");
        assertEquals(List.of("biome", "harbor", "cloudnative-pg"), steps.stream().map(ConnectorService.PhaseStep::id).toList());
        assertTrue(steps.stream().allMatch(ConnectorService.PhaseStep::ok), steps.toString());
        assertTrue(apps.isEmpty());
        assertFalse(switches.on("biome"));
        assertEquals(Map.of(), switches.pipelineParams());
        var order = inOrder(argo);
        order.verify(argo).deleteApp("connector-harbor");
        order.verify(argo).deleteApp("connector-cloudnative-pg");
    }

    @Test
    void oneFailureDoesNotStopTheRestOfThePhase() {
        var steps = service.switchPhase("4", true, "alice");      // Loki needs Prometheus (phase 1), not installed
        var loki = steps.stream().filter(s -> s.id().equals("loki")).findFirst().orElseThrow();
        assertFalse(loki.ok());
        assertTrue(loki.detail().contains("Prometheus"), loki.detail());
        assertTrue(steps.stream().filter(s -> s.id().equals("gatus")).findFirst().orElseThrow().ok());
        assertEquals(Set.of("connector-gatus"), apps);
    }

    @Test
    void phasesReportHowManyMembersAreOnAndSkipPlanned() {
        var before = service.phases();
        assertEquals(List.of("1", "4", "5"), before.stream().map(p -> p.get("phase")).toList());
        var five = before.get(2);
        assertEquals(0L, five.get("on"));
        assertEquals(3, five.get("total"));                      // Velero is planned: not switchable
        service.switchPhase("5", true, "alice");
        assertEquals(3L, service.phases().get(2).get("on"));
        assertThrows(IllegalArgumentException.class, () -> service.switchPhase("9", true, "alice"));
    }

    @Test
    void pipelineStepShowsOffUntilSwitchedOn() {
        var biome = service.list().stream().filter(m -> "biome".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(ConnectorService.Status.OFF, biome.get("status"));
        assertEquals(false, biome.get("switchedOn"));
    }

    @Test
    void onlyKnownSwitchesCanBeSet() {
        assertThrows(IllegalArgumentException.class, () -> switches.set("anything", true, "alice"));
    }
}
