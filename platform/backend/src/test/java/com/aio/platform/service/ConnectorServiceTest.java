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
    /** The aio-catalog build (dist/catalog.yaml) copied as a fixture: the real entries, values paths and pins. */
    static final String FIXTURE = fixture();

    private static String fixture() {
        try {
            return Path.of(ConnectorServiceTest.class.getResource("/connectors/test-catalog.yaml").toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConnectorCatalog catalog(String cloud) {
        return new ConnectorCatalog(FIXTURE, cloud);
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
        assertEquals(List.of("$values/values/kube-prometheus-stack/values.yaml"), J.get(sources.get(0), "helm", "valueFiles"));
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
        assertThrows(IllegalArgumentException.class, () -> s.disable("karpenter"));
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
        Map<String, Object> planned = s.list().stream().filter(m -> "karpenter".equals(m.get("id"))).findFirst().orElseThrow();
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
        assertEquals("extras/kube-green-sleepinfo", J.str(sources.get(2), "path"));
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
        assertEquals(List.of("Argo CD app", "Deployment kube-green-controller-manager"),
                r.probes().stream().map(ConnectorService.ProbeResult::name).toList());
        assertFalse(r.ok());
    }

    @Test
    void connectorWithoutDeploymentOrProbesIsTestedThroughArgoHealth() {
        ArgoCdService argo = mock(ArgoCdService.class);
        ConnectorService s = service(catalog("any"), argo);
        when(argo.getApp("connector-renovate", false)).thenReturn(Map.of("status", Map.of(
                "health", Map.of("status", "Healthy"), "sync", Map.of("status", "Synced"))));
        var ok = s.test("renovate");
        assertTrue(ok.ok());
        assertEquals("Healthy, Synced", ok.probes().get(0).detail());
        when(argo.getApp("connector-renovate", false)).thenReturn(Map.of("status", Map.of(
                "health", Map.of("status", "Degraded"))));
        assertFalse(s.test("renovate").ok());
    }

    @Test
    void aksGetsItsOwnValuesAndIngress() {
        ConnectorCatalog aks = catalog("aks");
        ConnectorService s = service(aks, mock(ArgoCdService.class));
        var velero = J.list(s.appSpec(aks.find("velero").orElseThrow(), "12.2.3"), "spec", "sources");
        assertEquals(List.of("$values/values/velero/values.yaml", "$values/values/velero/values-aks.yaml"),
                J.get(velero.get(0), "helm", "valueFiles"));
        var prom = J.list(s.appSpec(aks.find("prometheus").orElseThrow(), "92.17.1"), "spec", "sources");
        assertEquals(List.of("$values/values/kube-prometheus-stack/values.yaml",
                "$values/values/kube-prometheus-stack/values-aks.yaml"), J.get(prom.get(0), "helm", "valueFiles"));
        Map<String, Object> nginx = s.list().stream().filter(m -> "ingress-nginx".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(ConnectorService.Status.NOT_FOR_THIS_CLOUD, nginx.get("status"));
        assertTrue(aks.find("traefik").orElseThrow().supports("aks"));
        assertFalse(aks.find("traefik").orElseThrow().supports("gke"));
    }

    @Test
    void cloudSpecificValuesAreAddedForThatCloudOnly() {
        var velero = catalog("any").find("velero").orElseThrow();
        var gke = J.list(service(catalog("gke"), mock(ArgoCdService.class)).appSpec(velero, "12.2.3"), "spec", "sources");
        assertEquals(List.of("$values/values/velero/values.yaml", "$values/values/velero/values-gke.yaml"),
                J.get(gke.get(0), "helm", "valueFiles"));
        var any = J.list(service(catalog("any"), mock(ArgoCdService.class)).appSpec(velero, "12.2.3"), "spec", "sources");
        assertEquals(List.of("$values/values/velero/values.yaml"), J.get(any.get(0), "helm", "valueFiles"));
        String base = "connectors:\n  - {id: x, name: X, category: C, kind: managed, namespace: ns, chartRepo: https://r, chart: c,"
                + " version: 1.2.3, releaseName: x, valuesPath: connectors/x/values.yaml";
        assertThrows(IllegalStateException.class, () -> parse(base + ", cloudValues: {any: connectors/x/a.yaml}}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base + ", cloudValues: {gke: ../x.yaml}}\n"));
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

    @Test
    void builtInCatalogIsCoreOnly() {
        ConnectorCatalog builtIn = new ConnectorCatalog("", "any");
        assertFalse(builtIn.all().isEmpty());
        assertTrue(builtIn.all().stream().allMatch(c -> c.kindEnum() == ConnectorCatalog.Kind.CORE));
        assertTrue(builtIn.source().location().startsWith("built-in"));
    }

    @Test
    void valuesAndExtrasComeFromTheCatalogRepoAtItsRevision() {
        ConnectorCatalog c = new ConnectorCatalog(FIXTURE, "aks", "sarvaikam",
                "https://github.com/example/aio-catalog.git", "v2026.11.1", "");
        ConnectorService s = service(c, mock(ArgoCdService.class));
        var kg = J.list(s.appSpec(c.find("kube-green").orElseThrow(), "0.7.1"), "spec", "sources");
        assertEquals("https://github.com/example/aio-catalog.git", J.str(kg.get(1), "repoURL"));
        assertEquals("v2026.11.1", J.str(kg.get(1), "targetRevision"));
        assertEquals("https://github.com/example/aio-catalog.git", J.str(kg.get(2), "repoURL"));
        var prom = J.list(s.appSpec(c.find("prometheus").orElseThrow(), "92.17.1"), "spec", "sources");
        assertEquals(List.of("$values/values/kube-prometheus-stack/values.yaml",
                "$values/values/kube-prometheus-stack/values-aks.yaml",
                "$values/values/kube-prometheus-stack/site-sarvaikam.yaml"), J.get(prom.get(0), "helm", "valueFiles"));
    }

    @Test
    void githubRepoResolvesToTheBuiltCatalogAtTheTag() {
        assertEquals("https://raw.githubusercontent.com/satrishabh/aio-catalog/v2026.11.1/dist/catalog.yaml",
                ConnectorCatalog.resolveLocation("", "https://github.com/satrishabh/aio-catalog.git", "v2026.11.1"));
        assertThrows(IllegalStateException.class,
                () -> ConnectorCatalog.resolveLocation("", "https://github.com/satrishabh/aio-catalog.git", null));
        assertThrows(IllegalStateException.class,
                () -> ConnectorCatalog.resolveLocation("", "https://gitlab.example.com/x/aio-catalog.git", "v1"));
        assertThrows(IllegalStateException.class,
                () -> ConnectorCatalog.resolveLocation("http://insecure.example.com/catalog.yaml", null, null));
        assertThrows(IllegalStateException.class, () -> new ConnectorCatalog("", "any", "", "", "../../etc", ""));
    }

    @Test
    void unreachableCatalogFallsBackToCoreAndReportsTheError() {
        ConnectorCatalog c = new ConnectorCatalog("https://127.0.0.1:1/catalog.yaml", "any", "", "", "", "");
        assertTrue(c.all().stream().allMatch(x -> x.kindEnum() == ConnectorCatalog.Kind.CORE));
        assertNotNull(c.source().lastError());
    }

    @Test
    void metadataAndSsoAreExposed() {
        ConnectorService s = service(catalog("any"), mock(ArgoCdService.class));
        Map<String, Object> harbor = s.list().stream().filter(m -> "harbor".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals("Apache-2.0", harbor.get("license"));
        assertEquals("CNCF (graduated)", harbor.get("foundation"));
        assertEquals("oidc", harbor.get("sso"));
        String base = "connectors:\n  - {id: x, name: X, category: C, kind: planned";
        assertThrows(IllegalStateException.class, () -> parse(base + ", sso: {mode: magic}}\n"));
        assertThrows(IllegalStateException.class, () -> parse(base + ", homepage: http://x}\n"));
    }
}
