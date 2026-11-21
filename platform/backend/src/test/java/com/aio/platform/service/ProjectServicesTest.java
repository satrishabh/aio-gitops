package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.AttachedService;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.AttachedServiceRepo;
import com.aio.platform.repo.ProjectRepo;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Project -> Services: catalog bindings, substitution, values file edits, attach / detach checks, usage. */
class ProjectServicesTest {
    /** aio-catalog dist/catalog.yaml with the first-wave bindings (manifestsText inlined by tools/build.py). */
    static final String BINDINGS = fixture("/connectors/bindings-catalog.yaml");

    private static String fixture(String name) {
        try {
            return Path.of(ProjectServicesTest.class.getResource(name).toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String VALUES = """
            # Managed by AIO platform - image.tag is updated by CI / promotions
            image:
              repository: ghcr.io/example/shop
              tag: "1a2b3c4d5e6f"
            replicaCount: 1
            env:
              - name: LOG_LEVEL   # kept as written
                value: "info"
            ingress:
              enabled: true
              host: shop-dev.example.com
            """;

    private final Map<String, String> files = new HashMap<>();          // env -> values file in "git"
    private final List<String> commits = new ArrayList<>();
    private final List<AttachedService> rows = new ArrayList<>();
    private final Map<String, Secret> secrets = new HashMap<>();         // ns/name -> Secret
    private final Map<String, ConnectorService.Status> status = new HashMap<>();
    private final Map<String, String> clusterOf = new HashMap<>(Map.of("dev", "in-cluster", "staging", "in-cluster", "prod", "in-cluster"));
    private ConnectorCatalog catalog;
    private ConnectorService connectors;
    private ProjectServicesService service;
    private AuditService audit;
    private Project shop;
    private AttachedServiceRepo repo;
    private ProjectRepo projects;

    @BeforeEach
    void setUp() {
        catalog = new ConnectorCatalog(BINDINGS, "aks");
        shop = new Project();
        shop.id = 7L;
        shop.name = "shop";
        shop.imageRepo = "ghcr.io/example/shop";
        for (String env : GitOpsService.ENVS) files.put(env, VALUES.replace("shop-dev", "shop-" + env));

        GitOpsService gitops = mock(GitOpsService.class);
        when(gitops.readRaw(any(), anyString())).thenAnswer(i -> {
            String env = i.getArgument(1);
            return new GitOpsService.RawValues("apps/shop/values-" + env + ".yaml", files.get(env), "sha-" + files.get(env).hashCode());
        });
        doAnswer(i -> {
            String env = i.getArgument(1), yaml = i.getArgument(2), sha = i.getArgument(3);
            GitOpsService.validateValues(yaml, shop.imageRepo, env);
            assertEquals("sha-" + files.get(env).hashCode(), sha);
            files.put(env, yaml);
            commits.add(i.getArgument(4));
            return null;
        }).when(gitops).writeRaw(any(), anyString(), anyString(), anyString(), anyString());

        ClusterRegistry clusters = mock(ClusterRegistry.class);
        when(clusters.clusterFor(anyString())).thenAnswer(i -> new AppProperties.Cluster(clusterOf.get(i.<String>getArgument(0)),
                "https://x", List.of(), null, null));
        when(clusters.inCluster(any())).thenAnswer(i -> ClusterRegistry.IN_CLUSTER.equals(i.<AppProperties.Cluster>getArgument(0).name()));

        connectors = mock(ConnectorService.class);
        when(connectors.describe(any())).thenAnswer(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status.getOrDefault(i.<ConnectorCatalog.Connector>getArgument(0).id(), ConnectorService.Status.HEALTHY));
            return m;
        });

        repo = mock(AttachedServiceRepo.class);
        when(repo.findByProjectIdOrderByConnectorIdAscEnvAsc(anyLong())).thenAnswer(i -> List.copyOf(rows));
        when(repo.findByProjectIdAndConnectorIdAndEnv(anyLong(), anyString(), anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.projectId.equals(i.getArgument(0)) && r.connectorId.equals(i.getArgument(1)) && r.env.equals(i.getArgument(2)))
                .findFirst());
        when(repo.findByConnectorIdAndStatusOrderByProjectIdAscEnvAsc(anyString(), anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.connectorId.equals(i.getArgument(0)) && r.status.equals(i.getArgument(1))).toList());
        when(repo.save(any())).thenAnswer(i -> {
            AttachedService r = i.getArgument(0);
            if (r.id == null) {
                r.id = (long) rows.size() + 1;
                rows.add(r);
            }
            return r;
        });
        doAnswer(i -> rows.remove(i.<AttachedService>getArgument(0))).when(repo).delete(any());
        projects = mock(ProjectRepo.class);
        when(projects.findById(7L)).thenReturn(Optional.of(shop));

        audit = mock(AuditService.class);
        service = new ProjectServicesService(catalog, connectors, clusters, gitops, repo, new ServiceUsage(repo, projects), audit,
                mock(KubernetesClient.class));
        service.secrets = new ProjectServicesService.Secrets() {
            public Secret get(String ns, String name) {
                return secrets.get(ns + "/" + name);
            }

            public void apply(Secret s) {
                secrets.put(s.getMetadata().getNamespace() + "/" + s.getMetadata().getName(), s);
            }

            public void delete(String ns, String name) {
                secrets.remove(ns + "/" + name);
            }
        };
        secrets.put("valkey/valkey-users", new SecretBuilder().withNewMetadata().withName("valkey-users").withNamespace("valkey")
                .endMetadata().addToData("default", "c2VjcmV0").build());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    private Map<String, Object> attach(String connector, String env, Map<String, String> options) {
        return service.attach(shop, new ProjectServicesService.AttachRequest(connector, env, options), "alice");
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void catalogParsesFirstWaveBindings() {
        var pg = catalog.find("cloudnative-pg").orElseThrow().binding();
        assertEquals(ConnectorCatalog.Scope.INSTANCE, pg.scopeEnum());
        assertTrue(pg.manifestsText().contains("kind: Cluster"));
        assertEquals(List.of("dev", "staging"), catalog.find("valkey").orElseThrow().binding().envList());
        assertEquals(ConnectorCatalog.Scope.SHARED, catalog.find("valkey").orElseThrow().binding().scopeEnum());
        assertEquals(ConnectorCatalog.Scope.CONFIG, catalog.find("dapr").orElseThrow().binding().scopeEnum());
        for (String id : List.of("cloudnative-pg", "valkey", "strimzi", "opensearch", "opentelemetry-collector", "temporal",
                "dapr", "langfuse", "milvus"))
            assertNotNull(catalog.find(id).orElseThrow().binding(), id);
        // A catalog without any binding (the older fixture) still loads, nothing is bindable
        var old = new ConnectorCatalog(ConnectorServiceTest.FIXTURE, "any");
        assertFalse(old.all().isEmpty());
        assertTrue(old.all().stream().allMatch(c -> c.binding() == null));
    }

    private static final String ENTRY = """
            connectors:
            - id: valkey
              name: Valkey
              category: Data
              kind: managed
              namespace: valkey
              releaseName: valkey
              chartRepo: https://valkey.io/valkey-helm
              chart: valkey
              version: 0.12.1
              valuesPath: values/valkey/values.yaml
              binding:
            """;

    private static List<ConnectorCatalog.Connector> parse(String binding) {
        return ConnectorCatalog.parse(new ByteArrayInputStream((ENTRY + binding.indent(4)).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void catalogValidatesBindings() {
        // $(VAR) is Kubernetes env expansion, not a placeholder; unknown fields in a binding are ignored
        var ok = parse("""
                label: Cache
                scope: config
                somethingNew: true
                env:
                  - { name: A, value: '${app}-${env}' }
                  - { name: B, value: 'x:$(A)' }
                """);
        assertNotNull(ok.getFirst().binding());
        for (String bad : List.of(
                "label: X\nscope: config\nenv: [{ name: A, value: '${nope}' }]\n",                   // unknown placeholder
                "label: X\nscope: config\nenv: [{ name: A, value: '${opt.size}' }]\n",               // option not declared
                "label: X\nscope: weird\nenv: [{ name: A, value: a }]\n",                            // scope
                "label: X\nscope: config\nenvs: [qa]\nenv: [{ name: A, value: a }]\n",                // env
                "label: X\nscope: config\nmanifestsText: 'kind: X'\nenv: [{ name: A, value: a }]\n", // config + manifests
                "label: X\nscope: instance\nenv: [{ name: A, value: a }]\n",                         // instance without manifests
                "label: X\nscope: config\nenv: [{ name: A, value: a, secret: s, key: k }]\n",        // value and secret
                "label: X\nscope: config\noptions: [{ id: s, default: 9Gi, choices: [1Gi] }]\nenv: [{ name: A, value: a }]\n",
                "label: X\nscope: config\nmanifests: bindings/x.yaml\nenv: [{ name: A, value: a }]\n")) // no manifestsText
            assertThrows(IllegalStateException.class, () -> parse(bad), bad);
    }

    // ------------------------------------------------------------------ substitution

    @Test
    void substitutionReplacesKnownPlaceholdersAndFailsOnUnknown() {
        Map<String, String> vars = Map.of("app", "shop", "name", "shop-valkey", "opt.size", "1Gi");
        assertEquals("shop-valkey-1Gi $(KEEP)", ProjectServicesService.substitute("${name}-${opt.size} $(KEEP)", vars, "t"));
        var e = assertThrows(IllegalArgumentException.class, () -> ProjectServicesService.substitute("${opt.disk}", vars, "manifests"));
        assertTrue(e.getMessage().contains("${opt.disk}"), e.getMessage());
    }

    @Test
    void optionsAreCheckedAgainstChoicesAndDefaulted() {
        var pg = catalog.find("cloudnative-pg").orElseThrow().binding();
        assertEquals(Map.of("instances", "1", "storage", "1Gi"), ProjectServicesService.resolveOptions(pg, null));
        assertEquals("5Gi", ProjectServicesService.resolveOptions(pg, Map.of("storage", "5Gi")).get("storage"));
        assertThrows(IllegalArgumentException.class, () -> ProjectServicesService.resolveOptions(pg, Map.of("storage", "7Gi")));
        assertThrows(IllegalArgumentException.class, () -> ProjectServicesService.resolveOptions(pg, Map.of("color", "red")));
    }

    // ------------------------------------------------------------------ values file edits

    @Test
    void servicesBlockEditKeepsTheRestOfTheFile() {
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("dapr", Map.of("podAnnotations", Map.of("dapr.io/enabled", "true")));
        String added = ServicesValues.write(VALUES, services);
        assertTrue(added.startsWith(VALUES), added);                     // byte for byte, comments included
        assertTrue(added.contains(ServicesValues.MARKER + "\nservices:\n"));

        String withTail = added + "pdb:\n  enabled: false\n";
        Map<String, Object> two = new LinkedHashMap<>(services);
        two.put("x", Map.of("manifests", "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: a\n\n# gap above\ndata: {}\n"));
        String updated = ServicesValues.write(withTail, two);
        assertTrue(updated.startsWith(VALUES) && updated.endsWith("pdb:\n  enabled: false\n"), updated);
        assertEquals(two, ServicesValues.read(updated));
        assertEquals(1, updated.split(java.util.regex.Pattern.quote(ServicesValues.MARKER), -1).length - 1);

        String removed = ServicesValues.write(updated, Map.of());
        assertEquals(VALUES + "pdb:\n  enabled: false\n", removed);
    }

    // ------------------------------------------------------------------ attach / detach

    @Test
    @SuppressWarnings("unchecked")
    void attachInstanceWritesManifestsAndEnvIntoTheValuesFile() {
        var view = attach("cloudnative-pg", "dev", Map.of("storage", "5Gi"));
        assertEquals("ATTACHED", view.get("status"));
        assertEquals(List.of("services: attach cloudnative-pg to shop dev"), commits);
        String dev = files.get("dev");
        assertTrue(dev.startsWith(VALUES), dev);
        Map<String, Object> block = (Map<String, Object>) J.get(yaml(dev), "services", "cloudnative-pg");
        String manifests = (String) block.get("manifests");
        assertTrue(manifests.contains("name: shop-cloudnative-pg"), manifests);
        assertTrue(manifests.contains("5Gi"), manifests);
        assertFalse(manifests.contains("${"), manifests);
        List<Map<String, Object>> env = (List<Map<String, Object>>) block.get("env");
        assertEquals("DATABASE_URL", env.getFirst().get("name"));
        assertEquals(Map.of("secretKeyRef", Map.of("name", "shop-cloudnative-pg-app", "key", "uri")), env.getFirst().get("valueFrom"));
        assertEquals(Map.of("name", "PGHOST", "value", "shop-cloudnative-pg-rw"), env.get(1));
        assertEquals(1, rows.size());
        assertEquals("{\"instances\":\"1\",\"storage\":\"5Gi\"}", rows.getFirst().options);
        verify(audit).record(eq("alice"), eq("project.service-attach"), eq("shop/dev"), contains("cloudnative-pg"));
        assertEquals(VALUES.replace("shop-dev", "shop-staging"), files.get("staging"));   // other envs untouched
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachSharedCopiesTheSecretAndKeepsEnvOrderForExpansion() {
        attach("valkey", "dev", null);
        Secret copy = secrets.get("dev/shop-valkey");
        assertNotNull(copy);
        assertEquals("c2VjcmV0", copy.getData().get("password"));
        assertEquals("shop", copy.getMetadata().getLabels().get("aio.platform/project"));
        assertEquals("valkey", copy.getMetadata().getLabels().get("aio.platform/service"));
        List<Map<String, Object>> env = (List<Map<String, Object>>) J.get(yaml(files.get("dev")), "services", "valkey", "env");
        List<String> names = env.stream().map(e -> (String) e.get("name")).toList();
        assertTrue(names.indexOf("VALKEY_PASSWORD") < names.indexOf("REDIS_URL"), names.toString());
        assertTrue(env.get(names.indexOf("REDIS_URL")).get("value").toString().contains("$(VALKEY_PASSWORD)"));
        assertEquals("shop:dev:", env.get(names.indexOf("VALKEY_KEY_PREFIX")).get("value"));

        var result = service.detach(shop, "valkey", "dev", null, "bob");
        assertFalse(result.dataDeleted());
        assertNull(secrets.get("dev/shop-valkey"));
        assertNull(J.get(yaml(files.get("dev")), "services"));
        assertEquals(VALUES, files.get("dev"));
        assertTrue(rows.isEmpty());
        verify(audit).record(eq("bob"), eq("project.service-detach"), eq("shop/dev"), contains("valkey"));
    }

    @Test
    void attachRefusesWhereTheConnectorCannotRun() {
        // dev/staging-only connector in prod
        var e1 = assertThrows(IllegalArgumentException.class, () -> attach("valkey", "prod", null));
        assertTrue(e1.getMessage().contains("dev, staging"), e1.getMessage());
        // env on another cluster
        clusterOf.put("prod", "aks-prod");
        var e2 = assertThrows(IllegalStateException.class, () -> attach("cloudnative-pg", "prod", null));
        assertTrue(e2.getMessage().contains("aks-prod"), e2.getMessage());
        // not switched on / degraded
        status.put("temporal", ConnectorService.Status.NOT_INSTALLED);
        assertThrows(IllegalStateException.class, () -> attach("temporal", "dev", null));
        status.put("dapr", ConnectorService.Status.DEGRADED);
        assertThrows(IllegalStateException.class, () -> attach("dapr", "dev", null));
        assertTrue(commits.isEmpty());
        assertTrue(rows.isEmpty());

        var overview = service.overview(shop);
        @SuppressWarnings("unchecked")
        var valkey = ((List<Map<String, Object>>) overview.get("available")).stream()
                .filter(m -> "valkey".equals(m.get("connector"))).findFirst().orElseThrow();
        assertEquals("ENV_NOT_ALLOWED", J.str(valkey, "envs", "prod", "reason"));
        assertEquals("available", J.str(valkey, "envs", "dev", "state"));
    }

    @Test
    void sharedSecretOfSomeoneElseIsNotOverwritten() {
        secrets.put("dev/shop-valkey", new SecretBuilder().withNewMetadata().withName("shop-valkey").withNamespace("dev")
                .endMetadata().addToData("password", "bWluZQ==").build());
        assertThrows(IllegalStateException.class, () -> attach("valkey", "dev", null));
        assertEquals("bWluZQ==", secrets.get("dev/shop-valkey").getData().get("password"));
        assertEquals(AttachedService.FAILED, rows.getFirst().status);
        assertEquals(VALUES, files.get("dev"));
    }

    @Test
    void detachingAnInstanceNeedsTheProjectNameAndReportsOrphanedSecrets() {
        attach("opensearch", "staging", null);
        // the operator's admin Secret has no owner reference, so pruning the cluster leaves it
        secrets.put("staging/shop-opensearch-admin-password", new SecretBuilder().withNewMetadata()
                .withName("shop-opensearch-admin-password").withNamespace("staging").endMetadata().build());
        assertThrows(IllegalArgumentException.class, () -> service.detach(shop, "opensearch", "staging", null, "bob"));
        assertThrows(IllegalArgumentException.class, () -> service.detach(shop, "opensearch", "staging", "nope", "bob"));
        var result = service.detach(shop, "opensearch", "staging", "shop", "bob");
        assertTrue(result.dataDeleted());
        assertEquals(List.of("staging/shop-opensearch-admin-password"), result.leftBehind());
        assertEquals(VALUES.replace("shop-dev", "shop-staging"), files.get("staging"));
        assertEquals(List.of("services: attach opensearch to shop staging", "services: detach opensearch from shop staging"), commits);
    }

    @Test
    void usageListsProjectsAndEnvs() {
        attach("dapr", "staging", null);
        attach("dapr", "dev", Map.of());
        var uses = service.usage("dapr");
        assertEquals(1, uses.size());
        assertEquals("shop", uses.getFirst().project());
        assertEquals(List.of("dev", "staging"), uses.getFirst().envs());
        assertTrue(files.get("dev").contains("\"dapr.io/app-id\": \"shop\""), files.get("dev"));
    }

    @Test
    void disableAndPhaseOffAreRefusedWhileProjectsUseTheConnector() {
        attach("dapr", "dev", null);
        ArgoCdService argo = mock(ArgoCdService.class);
        when(argo.getApp("connector-dapr", false)).thenReturn(Map.of());
        AppProperties props = mock(AppProperties.class);
        ConnectorService real = new ConnectorService(catalog, argo, mock(KubernetesClient.class), props);
        real.usage = new ServiceUsage(repo, projects);

        var e = assertThrows(IllegalStateException.class, () -> real.disable("dapr"));
        assertTrue(e.getMessage().contains("shop (dev)"), e.getMessage());
        verify(argo, never()).deleteApp(any());

        String phase = catalog.find("dapr").orElseThrow().phase();
        var step = real.switchPhase(phase, false, "admin").stream().filter(s -> s.id().equals("dapr")).findFirst().orElseThrow();
        assertFalse(step.ok());
        assertTrue(step.detail().contains("shop"), step.detail());
        verify(argo, never()).deleteApp("connector-dapr");

        var listed = real.list().stream().filter(m -> "dapr".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(1, ((List<?>) listed.get("usedBy")).size());

        real.disable("dapr", true);                       // admin force
        verify(argo).deleteApp("connector-dapr");
    }
}
