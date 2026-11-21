package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.ClusterRecord;
import com.aio.platform.domain.Deployment;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.DomainRepo;
import com.aio.platform.repo.ProjectRepo;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Environments as data: ordering, promoteFrom, approval, adding/removing an environment, metrics. */
class EnvironmentsTest {

    /** The live configuration on 2026-11-21: one AKS cluster "azure" hosting dev, staging and prod. */
    static AppProperties liveProps() {
        var azure = new AppProperties.Cluster("azure", "https://kubernetes.default.svc", List.of("dev", "staging", "prod"),
                "52.140.55.184.nip.io", null, "52.140.55.184", "letsencrypt-azure", null, false, "aio-azure", "traefik");
        var gitops = new AppProperties.GitOps("https://github.com/o/aio-gitops.git", "o", "aio-gitops", "main", "apps", "helm/app");
        var argo = new AppProperties.ArgoCd("https://argocd-server.argocd.svc", "t", "apps", "https://kubernetes.default.svc", true);
        return new AppProperties(null, "ghcr.io/o", "o", null, null, gitops, argo, null, null, null, List.of(azure));
    }

    static ClusterRecord outside() {
        ClusterRecord c = new ClusterRecord();
        c.name = "oci-edge";
        c.cloud = "oke";
        c.server = "https://10.9.8.7:6443";
        c.baseDomain = "129.1.2.3.nip.io";
        c.ingressIp = "129.1.2.3";
        c.ingressController = "nginx";
        c.ingressClass = "nginx";
        c.ingressNamespace = "ingress-nginx";
        c.tls = false;
        return c;
    }

    /** Today's state plus an outside cluster (not yet used by any env). */
    static EnvironmentRegistry registry() {
        EnvironmentRegistry r = EnvironmentRegistry.fromConfig(liveProps(), "aks");
        r.save(outside());
        return r;
    }

    static EnvironmentRegistry fourEnvs() {
        EnvironmentRegistry r = registry();
        var e = new com.aio.platform.domain.Environment();
        e.name = "edge"; e.position = 4; e.promoteFrom = "prod"; e.cluster = "oci-edge"; e.namespace = "edge";
        e.approvalRequired = true; e.prodLike = true;
        r.save(e);
        return r;
    }

    static Project project(long id, String name) {
        Project p = new Project();
        p.id = id;
        p.name = name;
        p.imageRepo = "ghcr.io/o/" + name;
        return p;
    }

    // ------------------------------------------------------------------ automatic re-apply of cluster settings

    @Test
    void clusterSettingsAreReappliedAutomaticallyOnlyWhereTheyDiffer() {
        Harness h = harness(registry());
        // shop's dev values still carry the old GCP nip.io host and the chart's nginx class; the rest have no ingress block
        when(h.github().getFile(eq("o"), eq("aio-gitops"), anyString(), eq("main"))).thenAnswer(inv ->
                "apps/shop/values-dev.yaml".equals(inv.getArgument(2))
                        ? new GitHubClient.FileContent("image:\n  tag: \"1\"\ningress:\n  enabled: true\n  host: shop-dev.34.180.3.18.nip.io\n", "sha")
                        : new GitHubClient.FileContent("image:\n  tag: \"1\"\n", "sha"));
        h.service().autoReapplyClusterSettings();

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(h.github(), atLeastOnce()).putFile(eq("o"), eq("aio-gitops"), path.capture(), eq("main"), content.capture(), any(), anyString());
        int i = path.getAllValues().indexOf("apps/shop/values-dev.yaml");
        assertTrue(i >= 0, path.getAllValues().toString());
        String dev = content.getAllValues().get(i);
        assertFalse(dev.contains("34.180.3.18"), dev);
        assertTrue(dev.contains("shop-dev.52.140.55.184.nip.io"), dev);
        assertTrue(dev.contains("aio-azure"), dev);
    }

    @Test
    void automaticReapplyCanBeSwitchedOff() {
        Harness h = harness(registry());
        h.service().autoClusterSettings = false;
        h.service().autoReapplyClusterSettings();
        verifyNoInteractions(h.github());
    }

    // ------------------------------------------------------------------ seeding (pure part; the DB part is in EnvironmentSeedTest)

    @Test
    void seedReproducesTodaysEnvironmentsAndCluster() {
        EnvironmentRegistry r = EnvironmentRegistry.fromConfig(liveProps(), "aks");
        assertEquals(List.of("dev", "staging", "prod"), r.names());
        assertNull(r.promoteFrom("dev"));
        assertEquals("dev", r.promoteFrom("staging"));
        assertEquals("staging", r.promoteFrom("prod"));
        assertTrue(r.approvalRequired("prod"));
        assertFalse(r.approvalRequired("staging"));
        assertEquals(Set.of("prod"), r.prodLike());
        assertTrue(r.spotAllowed("dev") && r.spotAllowed("staging") && !r.spotAllowed("prod"));
        for (String env : r.names()) {
            assertEquals("azure", r.clusterFor(env));
            assertEquals(env, r.namespaceOf(env));
        }
        // the derived cluster view equals the configured one
        var c = new ClusterRegistry(r, mock(KubernetesClient.class));
        var azure = c.clusterFor("prod");
        assertEquals(List.of("dev", "staging", "prod"), azure.envs());
        assertEquals("52.140.55.184.nip.io", azure.baseDomain());
        assertEquals("52.140.55.184", azure.ingressIp());
        assertEquals("letsencrypt-azure", azure.issuer());
        assertEquals("aio-azure", azure.ingressClass());
        assertEquals("traefik", azure.ingressController());
        assertFalse(c.tlsFor("dev"));
        assertTrue(c.inCluster(azure));
        assertEquals("aks", r.cluster("azure").orElseThrow().cloud);
    }

    @Test
    void fallbackWithoutClustersIsTheInClusterHub() {
        EnvironmentRegistry r = EnvironmentRegistry.defaults();
        assertEquals(List.of("dev", "staging", "prod"), r.names());
        assertEquals(ClusterRegistry.IN_CLUSTER, r.clusterFor("prod"));
        assertEquals("other", r.cluster(ClusterRegistry.IN_CLUSTER).orElseThrow().cloud);
    }

    // ------------------------------------------------------------------ ordering / promoteFrom / approval

    @Test
    void fourthEnvironmentGoesAfterProdWithApproval() {
        EnvironmentRegistry r = fourEnvs();
        assertEquals(List.of("dev", "staging", "prod", "edge"), r.names());
        assertEquals("prod", r.promoteFrom("edge"));
        assertEquals(List.of("edge"), r.promotesTo("prod"));
        assertTrue(r.approvalRequired("edge"));
        assertEquals(Set.of("prod", "edge"), r.prodLike());
        assertEquals("prod", r.trustClass("edge"));
        assertEquals("dev", r.trustClass("dev"));
        assertEquals("staging", r.trustClass("staging"));
        assertEquals("oci-edge", new ClusterRegistry(r, mock(KubernetesClient.class)).clusterFor("edge").name());
        assertEquals(List.of("edge"), r.envsOn("oci-edge"));

        assertTrue(PromotionService.validPath(r, "prod", "edge"));
        assertTrue(PromotionService.validPath(r, "dev", "staging"));
        assertFalse(PromotionService.validPath(r, "staging", "edge"));   // must go through prod
        assertFalse(PromotionService.validPath(r, "edge", "prod"));
        assertFalse(PromotionService.validPath(r, null, "dev"));
        assertEquals("dev->staging, staging->prod, prod->edge", PromotionService.allowedPaths(r));

        assertThrows(SecurityException.class, () -> ScaleService.validate(r, "edge", 1, false));
        assertDoesNotThrow(() -> ScaleService.validate(r, "edge", 1, true));
        assertThrows(IllegalArgumentException.class, () -> ScaleService.validate(r, "qa", 1, true));
    }

    @Test
    void spotIsNeverAllowedWhereProdLike() {
        EnvironmentRegistry r = fourEnvs();
        assertFalse(r.spotAllowed("edge"));
        var env = new com.aio.platform.domain.Environment();
        env.name = "x"; env.cluster = "azure"; env.spotAllowed = true; env.prodLike = true;
        assertFalse(EnvironmentRegistry.view(env).spotAllowed());
    }

    // ------------------------------------------------------------------ add / remove

    private record Harness(EnvironmentService service, EnvironmentRegistry envs, GitHubClient github, ArgoCdService argo,
                           ClusterService clusterService, ClusterAccess access, ProjectRepo projects, DomainRepo domains) {}

    private Harness harness(EnvironmentRegistry envs) {
        AppProperties props = liveProps();
        KubernetesClient hub = mock(KubernetesClient.class);
        ClusterRegistry clusters = new ClusterRegistry(envs, hub);
        GitHubClient github = mock(GitHubClient.class);
        RolloutService rollouts = mock(RolloutService.class);
        SpotDefaults spot = mock(SpotDefaults.class);
        GitOpsService gitops = new GitOpsService(github, props, rollouts, clusters, spot);
        gitops.environments = envs;
        ArgoCdService argo = mock(ArgoCdService.class);
        ClusterService clusterService = mock(ClusterService.class);
        ClusterAccess access = mock(ClusterAccess.class);
        when(access.allowDestination(any())).thenReturn(true);
        ProjectRepo projects = mock(ProjectRepo.class);
        when(projects.findAll()).thenReturn(List.of(project(1, "shop"), project(2, "user-profile-manager")));
        DomainRepo domains = mock(DomainRepo.class);
        var service = new EnvironmentService(envs, clusters, clusterService, access, gitops, argo, projects, domains, mock(AuditService.class));
        return new Harness(service, envs, github, argo, clusterService, access, projects, domains);
    }

    @Test
    void addingAnEnvironmentCreatesValuesFilesAndArgoAppsForEveryProject() {
        Harness h = harness(registry());
        // existing envs have values files; the new one has none yet
        when(h.github().getFile(eq("o"), eq("aio-gitops"), anyString(), eq("main"))).thenAnswer(inv -> {
            String path = inv.getArgument(2);
            return path.endsWith("values-edge.yaml") ? null : new GitHubClient.FileContent("image:\n  tag: \"1\"\n", "sha");
        });
        var result = h.service().add(new EnvironmentService.AddRequest("edge", "oci-edge", null, null, null, true, null), "admin");

        assertEquals("edge", result.environment().name());
        assertEquals("prod", result.environment().promoteFrom());   // default: after the last env
        assertTrue(result.environment().approvalRequired());         // default: promoted from a prod-like env
        assertEquals("edge", result.environment().namespace());      // default: the name
        verify(h.clusterService()).ensureNamespace("edge");
        verify(h.access()).setNamespaces("oci-edge", List.of("edge"));
        verify(h.access()).allowDestination("edge");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(h.github(), times(2)).putFile(eq("o"), eq("aio-gitops"), path.capture(), eq("main"), content.capture(), isNull(), anyString());
        assertEquals(List.of("apps/shop/values-edge.yaml", "apps/user-profile-manager/values-edge.yaml"), path.getAllValues());
        String shop = content.getAllValues().get(0);
        assertTrue(shop.contains("tag: \"0.0.0\""), shop);
        assertTrue(shop.contains("replicaCount: 0"), shop);
        assertTrue(shop.contains("host: shop-edge.129.1.2.3.nip.io"), shop);
        assertTrue(shop.contains("className: nginx"), shop);
        assertFalse(shop.contains("spot:"), shop);
        verify(h.argo()).upsertApp(argThat(p -> p.name.equals("shop")), eq("edge"));
        verify(h.argo()).upsertApp(argThat(p -> p.name.equals("user-profile-manager")), eq("edge"));
        verify(h.argo(), never()).upsertApp(any(), eq("prod"));
    }

    @Test
    void addValidatesNameClusterNamespaceAndPromotion() {
        Harness h = harness(registry());
        var s = h.service();
        assertThrows(IllegalArgumentException.class, () -> s.plan(new EnvironmentService.AddRequest("Edge", "oci-edge", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> s.plan(new EnvironmentService.AddRequest("e", "oci-edge", null, null, null, null, null)));
        assertThrows(IllegalStateException.class, () -> s.plan(new EnvironmentService.AddRequest("prod", "oci-edge", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> s.plan(new EnvironmentService.AddRequest("edge", "nowhere", null, null, null, null, null)));
        assertThrows(IllegalStateException.class, () -> s.plan(new EnvironmentService.AddRequest("qa", "azure", "staging", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> s.plan(new EnvironmentService.AddRequest("qa", "azure", "kube-system", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> s.plan(new EnvironmentService.AddRequest("qa", "azure", null, "nope", null, null, null)));
        var qa = s.plan(new EnvironmentService.AddRequest("qa", "azure", "qa-ns", "staging", null, null, true));
        assertFalse(qa.approvalRequired);   // promoted from a non-prod env
        assertTrue(qa.spotAllowed);
        assertEquals(4, qa.position);
    }

    @Test
    void removingIsRefusedWhileAProjectRunsThereOrAnotherEnvFollows() {
        Harness h = harness(fourEnvs());
        when(h.github().getFile(eq("o"), eq("aio-gitops"), eq("apps/shop/values-edge.yaml"), eq("main")))
                .thenReturn(new GitHubClient.FileContent("image:\n  tag: \"1.2\"\nreplicaCount: 1\n", "a"));
        when(h.github().getFile(eq("o"), eq("aio-gitops"), eq("apps/user-profile-manager/values-edge.yaml"), eq("main")))
                .thenReturn(new GitHubClient.FileContent("image:\n  tag: \"0.0.0\"\nreplicaCount: 0\n", "b"));
        var e = assertThrows(IllegalStateException.class, () -> h.service().remove("edge", "admin"));
        assertTrue(e.getMessage().contains("shop"), e.getMessage());
        assertTrue(assertThrows(IllegalStateException.class, () -> h.service().remove("prod", "admin")).getMessage().contains("built in"));
        assertTrue(assertThrows(IllegalStateException.class, () -> h.service().remove("dev", "admin")).getMessage().contains("first"));

        // scaled to 0 everywhere: removed, apps and files deleted, Argo CD namespaces updated
        when(h.github().getFile(eq("o"), eq("aio-gitops"), eq("apps/shop/values-edge.yaml"), eq("main")))
                .thenReturn(new GitHubClient.FileContent("image:\n  tag: \"1.2\"\nreplicaCount: 0\nscaledDown: true\n", "a"));
        h.service().remove("edge", "admin");
        assertFalse(h.envs().exists("edge"));
        verify(h.argo()).deleteApp("shop-edge");
        verify(h.github()).deleteFile(eq("o"), eq("aio-gitops"), eq("apps/shop/values-edge.yaml"), eq("main"), eq("a"), anyString());
        verify(h.access()).setNamespaces("oci-edge", List.of());
    }

    // ------------------------------------------------------------------ metrics

    private static Deployment dep(String env, String tag) {
        Deployment d = new Deployment();
        d.projectId = 1L;
        d.environment = env;
        d.imageTag = tag;
        d.deployedAt = Instant.now().minusSeconds(60);
        return d;
    }

    @Test
    void metricsCountEveryProdLikeEnvAsProduction() {
        List<Deployment> deps = new ArrayList<>(List.of(dep("dev", "a"), dep("staging", "a"), dep("prod", "a"), dep("edge", "a")));
        Promotion rb = new Promotion();
        rb.kind = "ROLLBACK"; rb.toEnv = "edge"; rb.status = Promotion.DONE; rb.requestedAt = Instant.now();
        // only prod: numbers as before (the 4-arg overload is what existed)
        var before = MetricsService.compute(7, deps, List.of(), List.of(rb));
        assertEquals(1, before.prodDeploys());
        assertEquals(0, before.prodRollbacks());
        assertEquals(before, MetricsService.compute(7, deps, List.of(), List.of(rb), Set.of("prod")));
        // prod + edge prod-like
        var after = MetricsService.compute(7, deps, List.of(), List.of(rb), Set.of("prod", "edge"));
        assertEquals(2, after.prodDeploys());
        assertEquals(1, after.prodRollbacks());
        assertEquals(0.5, after.changeFailureRate());
        assertEquals(Map.of("dev", 1, "staging", 1, "prod", 1, "edge", 1), after.deploysByEnv());
        var day = MetricsService.daily(1, List.of(), deps, List.of(rb), Set.of("prod", "edge")).get(0);
        assertEquals(2L, day.get("deploysProd"));
        assertEquals(1L, day.get("rollbacks"));
    }
}
