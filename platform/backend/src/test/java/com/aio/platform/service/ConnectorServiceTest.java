package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aio.platform.config.AppProperties;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ConnectorServiceTest {
    private static ConnectorCatalog catalog(String cloud) {
        return new ConnectorCatalog("", cloud);
    }

    private static List<ConnectorCatalog.Connector> parse(String yaml) {
        return ConnectorCatalog.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static ConnectorService service(ConnectorCatalog catalog, ArgoCdService argo) {
        return service(catalog, argo, mock(KubernetesClient.class));
    }

    private static ConnectorService service(ConnectorCatalog catalog, ArgoCdService argo, KubernetesClient k8s) {
        AppProperties props = mock(AppProperties.class);
        when(props.gitops()).thenReturn(new AppProperties.GitOps(
                "https://github.com/example/gitops.git", "example", "gitops", "main", "apps", "helm/app"));
        return new ConnectorService(catalog, argo, k8s, props);
    }

    /** A client whose every Deployment lookup returns {@code d} (null = not found). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KubernetesClient k8sWithDeployment(Deployment d) {
        KubernetesClient k8s = mock(KubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation ops = mock(MixedOperation.class);
        NonNamespaceOperation inNs = mock(NonNamespaceOperation.class);
        RollableScalableResource res = mock(RollableScalableResource.class);
        when(k8s.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(ops);
        when(ops.inNamespace(anyString())).thenReturn(inNs);
        when(inNs.withName(anyString())).thenReturn(res);
        when(res.get()).thenReturn(d);
        return k8s;
    }

    @Test
    void builtInCatalogLoadsAndIsValid() {
        ConnectorCatalog c = catalog("any");
        assertTrue(c.all().size() >= 12);
        var prom = c.find("prometheus").orElseThrow();
        assertEquals(ConnectorCatalog.Kind.MANAGED, prom.kindEnum());
        assertEquals(List.of("92.17.1", "92.16.2"), prom.versions());
        assertEquals(4, prom.probeList().size());
    }

    @Test
    void managedAppIsMultiSourcePinnedAndServerSide() {
        ConnectorService s = service(catalog("any"), mock(ArgoCdService.class));
        var prom = catalog("any").find("prometheus").orElseThrow();
        Map<String, Object> app = s.appSpec(prom, "92.17.1");
        assertEquals("connector-prometheus", J.str(app, "metadata", "name"));
        assertEquals("connectors", J.str(app, "spec", "project"));
        var sources = J.list(app, "spec", "sources");
        assertEquals("kube-prometheus-stack", J.str(sources.get(0), "chart"));
        assertEquals("92.17.1", J.str(sources.get(0), "targetRevision"));
        assertEquals(List.of("$values/connectors/kube-prometheus-stack/values.yaml"), J.get(sources.get(0), "helm", "valueFiles"));
        assertEquals("values", J.str(sources.get(1), "ref"));
        assertEquals("monitoring", J.str(app, "spec", "destination", "namespace"));
        assertTrue(((List<?>) J.get(app, "spec", "syncPolicy", "syncOptions")).contains("ServerSideApply=true"));
    }

    @Test
    void onlyPinnedVersionsCanBeInstalled() {
        ArgoCdService argo = mock(ArgoCdService.class);
        ConnectorService s = service(catalog("any"), argo);
        assertThrows(IllegalArgumentException.class, () -> s.install("prometheus", "99.0.0"));
        assertEquals("92.16.2", s.install("prometheus", "92.16.2"));
        verify(argo).upsertApplication(any());
    }

    @Test
    void coreAndPlannedCannotBeInstalledOrRemoved() {
        ConnectorService s = service(catalog("any"), mock(ArgoCdService.class));
        assertThrows(IllegalArgumentException.class, () -> s.install("argocd", null));
        assertThrows(IllegalArgumentException.class, () -> s.disable("velero"));
        assertThrows(java.util.NoSuchElementException.class, () -> s.disable("nope"));
    }

    @Test
    void rollbackGoesToPreviousVersionOnlyWhenInstalled() {
        ArgoCdService argo = mock(ArgoCdService.class);
        ConnectorService s = service(catalog("any"), argo);
        when(argo.getApp("connector-prometheus", false)).thenReturn(null);   // Argo: not found
        assertThrows(IllegalStateException.class, () -> s.rollback("prometheus"));
        when(argo.getApp("connector-prometheus", false)).thenReturn(Map.of("spec", Map.of()));
        assertEquals("92.16.2", s.rollback("prometheus"));
    }

    @Test
    void argoForbiddenBecomesActionableError() {
        ArgoCdService argo = mock(ArgoCdService.class);
        doThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", null, null, null))
                .when(argo).upsertApplication(any());
        var e = assertThrows(IllegalStateException.class, () -> service(catalog("any"), argo).install("prometheus", null));
        assertTrue(e.getMessage().contains("connectors"));
    }

    @Test
    void statusDegradesGracefully() {
        ArgoCdService argo = mock(ArgoCdService.class);
        when(argo.getApp(anyString(), anyBoolean())).thenThrow(new RuntimeException("argo down"));
        ConnectorService s = service(catalog("any"), argo);
        Map<String, Object> prom = s.list().stream().filter(m -> "prometheus".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(ConnectorService.Status.UNKNOWN, prom.get("status"));
        Map<String, Object> planned = s.list().stream().filter(m -> "velero".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(ConnectorService.Status.PLANNED, planned.get("status"));
    }

    @Test
    void healthyAppWithFailedTestIsDegraded() {
        ArgoCdService argo = mock(ArgoCdService.class);
        when(argo.getApp("connector-prometheus", false)).thenReturn(Map.of(
                "spec", Map.of("sources", List.of(Map.of("targetRevision", "92.17.1"))),
                "status", Map.of("health", Map.of("status", "Healthy"), "sync", Map.of("status", "Synced"))));
        ConnectorService s = service(catalog("any"), argo);
        var r = s.test("prometheus");   // probes cannot reach *.svc from a unit test, so they fail
        assertFalse(r.ok());
        Map<String, Object> prom = s.list().stream().filter(m -> "prometheus".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(ConnectorService.Status.DEGRADED, prom.get("status"));
        assertEquals("92.17.1", prom.get("installedVersion"));
    }

    @Test
    void cloudSpecificConnectorsAreMarkedElsewhere() {
        ConnectorService s = service(catalog("gke"), mock(ArgoCdService.class));
        Map<String, Object> karpenter = s.list().stream().filter(m -> "karpenter".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(ConnectorService.Status.NOT_FOR_THIS_CLOUD, karpenter.get("status"));
        ConnectorService eks = service(catalog("eks"), mock(ArgoCdService.class));
        assertEquals(ConnectorService.Status.PLANNED,
                eks.list().stream().filter(m -> "karpenter".equals(m.get("id"))).findFirst().orElseThrow().get("status"));
    }

    @Test
    void catalogValidationRejectsUnsafeEntries() {
        String base = "connectors:\n  - {id: x, name: X, category: C, kind: managed, namespace: ns, chartRepo: https://r, chart: c,"
                + " version: 1.2.3, releaseName: x, valuesPath: connectors/x/values.yaml";
        assertDoesNotThrow(() -> parse(base + "}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base.replace("1.2.3", "latest") + "}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base.replace("https://r", "http://r") + "}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base.replace("connectors/x/values.yaml", "../x.yaml") + "}\n"));
        assertThrows(IllegalStateException.class,
                () -> parse(base + ", probes: [{name: p, url: 'http://169.254.169.254/latest'}]}\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(base + ", typo: 1}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base + "}\n" + base.substring("connectors:\n".length()) + "}\n"));
    }

    @Test
    void enableWaitsForRequiredConnector() {
        ArgoCdService argo = mock(ArgoCdService.class);
        ConnectorService s = service(catalog("any"), argo);
        when(argo.getApp("connector-prometheus", false)).thenReturn(null);
        var e = assertThrows(IllegalStateException.class, () -> s.install("opencost", null));
        assertTrue(e.getMessage().contains("Prometheus"), e.getMessage());
        verify(argo, never()).upsertApplication(any());
        when(argo.getApp("connector-prometheus", false)).thenReturn(Map.of("spec", Map.of()));
        assertEquals("2.5.33", s.install("opencost", null));
    }

    @Test
    void enableWaitsForRequiredCoreComponent() {
        KubernetesClient k8s = k8sWithDeployment(null);   // cert-manager Deployment not found
        ConnectorService s = service(catalog("any"), mock(ArgoCdService.class), k8s);
        var e = assertThrows(IllegalStateException.class, () -> s.install("kube-green", null));
        assertTrue(e.getMessage().contains("cert-manager"), e.getMessage());
    }

    @Test
    void disableRefusedWhileAnotherConnectorDependsOnIt() {
        ArgoCdService argo = mock(ArgoCdService.class);
        ConnectorService s = service(catalog("any"), argo);
        when(argo.getApp("connector-opencost", false)).thenReturn(Map.of("spec", Map.of()));
        var e = assertThrows(IllegalStateException.class, () -> s.disable("prometheus"));
        assertTrue(e.getMessage().contains("OpenCost"), e.getMessage());
        verify(argo, never()).deleteApp(any());
        when(argo.getApp("connector-opencost", false)).thenReturn(null);
        s.disable("prometheus");
        verify(argo).deleteApp("connector-prometheus");
    }

    @Test
    void extrasAreSyncedAsThirdSource() {
        ConnectorService s = service(catalog("any"), mock(ArgoCdService.class));
        var kg = catalog("any").find("kube-green").orElseThrow();
        var sources = J.list(s.appSpec(kg, "0.7.1"), "spec", "sources");
        assertEquals(3, sources.size());
        assertEquals("connectors/kube-green/sleepinfo", J.str(sources.get(2), "path"));
        assertEquals("main", J.str(sources.get(2), "targetRevision"));
        var prom = catalog("any").find("prometheus").orElseThrow();
        assertEquals(2, J.list(s.appSpec(prom, "92.17.1"), "spec", "sources").size());
    }

    @Test
    void testChecksTheDeploymentOfManagedConnectors() {
        ArgoCdService argo = mock(ArgoCdService.class);
        when(argo.getApp("connector-kube-green", false)).thenReturn(Map.of("spec", Map.of()));
        KubernetesClient k8s = k8sWithDeployment(null);
        var r = service(catalog("any"), argo, k8s).test("kube-green");
        assertEquals(1, r.probes().size());
        assertEquals("Deployment kube-green-controller-manager", r.probes().get(0).name());
        assertFalse(r.ok());
    }

    @Test
    void catalogValidatesRequiresAndExtras() {
        String base = "connectors:\n  - {id: x, name: X, category: C, kind: managed, namespace: ns, chartRepo: https://r, chart: c,"
                + " version: 1.2.3, releaseName: x, valuesPath: connectors/x/values.yaml";
        assertDoesNotThrow(() -> parse(base + ", extrasPath: connectors/x/extra}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base + ", extrasPath: ../etc}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base + ", extrasPath: /etc}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base + ", requires: [missing]}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base + ", requires: [x]}\n"));
    }

    /** Every managed connector must have its files in the repo and be allowed by the Argo CD connectors project. */
    @Test
    @SuppressWarnings("unchecked")
    void managedConnectorsMatchRepoFilesAndArgoProject() throws Exception {
        Path root = Path.of("../..").toAbsolutePath().normalize();
        Path projectFile = root.resolve("argocd/projects/connectors-project.yaml");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(projectFile), "not running inside the repo");
        Map<String, Object> project;
        try (InputStream in = new FileInputStream(projectFile.toFile())) {
            project = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
        Map<String, Object> spec = (Map<String, Object>) project.get("spec");
        List<String> repos = (List<String>) spec.get("sourceRepos");
        List<String> namespaces = ((List<Map<String, Object>>) spec.get("destinations")).stream()
                .map(d -> String.valueOf(d.get("namespace"))).toList();
        for (var c : catalog("any").all()) {
            if (c.kindEnum() != ConnectorCatalog.Kind.MANAGED) continue;
            assertTrue(Files.isRegularFile(root.resolve(c.valuesPath())), c.id() + ": missing " + c.valuesPath());
            if (c.extrasPath() != null)
                assertTrue(Files.isDirectory(root.resolve(c.extrasPath())), c.id() + ": missing " + c.extrasPath());
            assertTrue(repos.contains(c.chartRepo()), c.id() + ": " + c.chartRepo() + " not in connectors-project sourceRepos");
            assertTrue(namespaces.contains(c.namespace()), c.id() + ": namespace " + c.namespace() + " not in connectors-project");
        }
    }
}
