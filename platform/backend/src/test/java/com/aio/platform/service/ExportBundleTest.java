package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aio.platform.domain.Project;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ExportBundleTest {
    static final String WEBHOOK_SECRET = "whsec-never-in-the-zip-4f1c";
    static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    static final String CATALOG = """
            connectors:
              - id: cloudnative-pg
                name: CloudNativePG
                category: Database
                kind: managed
                phase: "5"
                namespace: cnpg-system
                releaseName: cnpg
                chart: cloudnative-pg
                chartRepo: https://cloudnative-pg.github.io/charts
                version: 0.26.1
                valuesPath: values/cloudnative-pg.yaml
                accessHint: No setup needed.
              - id: valkey
                name: Valkey
                category: Cache
                kind: managed
                phase: "5"
                namespace: valkey
                releaseName: valkey
                chart: valkey
                chartRepo: https://valkey.io/valkey-helm
                version: 0.9.2
                valuesPath: values/valkey.yaml
                cloudValues: { aks: values/valkey-aks.yaml }
                extrasPath: extras/valkey
              - id: langfuse
                name: Langfuse
                category: AI
                kind: managed
                phase: "7"
                namespace: langfuse
                releaseName: langfuse
                chart: langfuse
                chartRepo: https://langfuse.github.io/langfuse-k8s
                version: 1.5.0
                valuesPath: values/langfuse.yaml
                requires: [cloudnative-pg, biome]
                accessHint: Run setup/langfuse-secrets.sh (aio-catalog) before Enable.
              - id: biome
                name: Biome
                category: Lint
                kind: core
                namespace: tekton-pipelines
                deployment: biome
            """;

    static final String VALUES_DEV = """
            # Managed by AIO platform - image.tag is updated by CI / promotions
            image:
              repository: ghcr.io/acme/shop
              tag: "d73f307e781d"
            replicaCount: 1
            ingress:
              enabled: true
              host: shop-dev.example.com
              tls: true
            imagePullSecrets:
              - name: ghcr-pull
            env:
              - name: GREETING
                value: "hello"
              - name: STRIPE_API_KEY
                value: "sk_live_should_not_leak"
            resources:
              requests: { cpu: "50m", memory: "128Mi" }
              limits: { cpu: "500m", memory: "512Mi" }
            # Managed by AIO (Project → Services). Edit there, not by hand.
            services:
              cloudnative-pg:
                manifests: |
                  apiVersion: postgresql.cnpg.io/v1
                  kind: Cluster
                  metadata:
                    name: shop-cloudnative-pg
                  spec:
                    instances: 1
                    storage:
                      size: 1Gi
                env:
                  - { name: DATABASE_URL, valueFrom: { secretKeyRef: { name: shop-cloudnative-pg-app, key: uri } } }
                  - { name: PGHOST, value: shop-cloudnative-pg-rw }
              valkey:
                env:
                  - { name: VALKEY_PASSWORD, valueFrom: { secretKeyRef: { name: shop-valkey, key: password } } }
                  - { name: VALKEY_HOST, value: valkey.valkey.svc }
            """;

    static final String VALUES_PROD = """
            image:
              repository: ghcr.io/acme/shop
              tag: "0.0.0"
            replicaCount: 0
            ingress:
              enabled: true
              host: shop.example.com
            imagePullSecrets:
              - name: ghcr-pull
            """;

    static Project project() {
        Project p = new Project();
        p.id = 7L;
        p.name = "shop";
        p.repoOwner = "acme";
        p.repoName = "shop";
        p.defaultBranch = "main";
        p.dockerfile = "Dockerfile";
        p.contextDir = ".";
        p.testImage = "node:22-alpine";
        p.testCommand = "npm ci\nnpm test";
        p.buildStrategy = "auto";
        p.webhookSecret = WEBHOOK_SECRET;
        return p;
    }

    static Map<String, byte[]> chart() throws IOException {
        Path dir = Path.of("../../helm/app");
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            out.put("Chart.yaml", "apiVersion: v2\nname: app\nversion: 0.1.0\n".getBytes(StandardCharsets.UTF_8));
            return out;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList())
                out.put(dir.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
        }
        return out;
    }

    static ExportBundle.Inputs inputs(boolean all) throws IOException {
        List<ConnectorCatalog.Connector> cat = ConnectorCatalog.parse(new ByteArrayInputStream(CATALOG.getBytes(StandardCharsets.UTF_8)));
        Map<String, ConnectorCatalog.Connector> byId = new LinkedHashMap<>();
        cat.forEach(c -> byId.put(c.id(), c));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("dev", VALUES_DEV);
        values.put("prod", VALUES_PROD);
        Map<String, List<String>> used = ExportBundle.usedServices(values);
        List<String> ids = new ArrayList<>(used.keySet());
        if (all) ids.add("langfuse");
        List<ExportBundle.ConnectorExport> connectors = new ArrayList<>();
        List<ConnectorCatalog.Connector> missing = new ArrayList<>();
        for (ConnectorCatalog.Connector c : ExportBundle.withRequires(ids, byId::get)) {
            if (c.kindEnum() != ConnectorCatalog.Kind.MANAGED) {
                missing.add(c);
                continue;
            }
            Map<String, Object> binding = switch (c.id()) {
                case "cloudnative-pg" -> Map.of("scope", "instance", "exportNote", "Needs the CloudNativePG operator (connectors/cloudnative-pg).");
                case "valkey" -> Map.of("scope", "shared", "copySecret",
                        Map.of("from", "valkey-users", "keys", Map.of("default", "password"), "name", "${app}-valkey"));
                default -> null;
            };
            Map<String, byte[]> extras = "valkey".equals(c.id())
                    ? Map.of("networkpolicy.yaml", "kind: NetworkPolicy\n".getBytes(StandardCharsets.UTF_8)) : Map.of();
            Map<String, String> setup = "langfuse".equals(c.id()) ? Map.of("langfuse-secrets.sh", "#!/bin/sh\necho setup\n") : Map.of();
            connectors.add(new ExportBundle.ConnectorExport(c, c.version(), "replicas: 1\n",
                    "valkey".equals(c.id()) ? "storageClass: managed-csi\n" : null, extras, setup, binding,
                    used.getOrDefault(c.id(), List.of()), List.of()));
        }
        return new ExportBundle.Inputs(project(), AT, "from `helm/app` in acme/aio-gitops@0123456789ab", chart(), values,
                connectors, missing, all ? "aks" : "any", all, "acme/aio-catalog@v2026.11.4", List.of());
    }

    static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; )
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return out;
    }

    static byte[] zip(ExportBundle.Inputs in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ExportBundle.write(in, bytes);
        return bytes.toByteArray();
    }

    static Object yaml(String text) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    @Test
    void bundleHasEveryPartOfTheLayout() throws IOException {
        Map<String, String> files = unzip(zip(inputs(false)));
        for (String path : List.of("shop/README.md", "shop/chart/Chart.yaml", "shop/values/values-dev.yaml",
                "shop/values/values-prod.yaml", "shop/argocd/shop-dev.yaml", "shop/argocd/shop-prod.yaml",
                "shop/ci/github/workflows/build.yaml", "shop/connectors/README.md",
                "shop/connectors/cloudnative-pg/application.yaml", "shop/connectors/cloudnative-pg/values.yaml",
                "shop/connectors/valkey/application.yaml", "shop/connectors/valkey/extras/networkpolicy.yaml",
                "shop/secrets/ghcr-pull.example.yaml", "shop/secrets/shop-valkey.example.yaml"))
            assertTrue(files.containsKey(path), "missing " + path + " in " + files.keySet());
        assertFalse(files.containsKey("shop/values/values-staging.yaml"), "no staging values in git, none exported");
        assertFalse(files.containsKey("shop/connectors/langfuse/application.yaml"), "langfuse is not used");
        // instance services: the operator makes the Secret, so no template, but the README says so
        assertFalse(files.containsKey("shop/secrets/shop-cloudnative-pg-app.example.yaml"));
        assertTrue(files.get("shop/README.md").contains("shop-cloudnative-pg-app"));
        assertTrue(files.get("shop/README.md").contains("Needs the CloudNativePG operator"));
        assertTrue(files.get("shop/README.md").contains("https://shop-dev.example.com"));
        assertEquals("shop-aio-export-2026-11-21.zip", ExportBundle.fileName(project(), AT));
    }

    @Test
    void allConnectorsAddsInstalledOnesWithRequiresFirst() throws IOException {
        Map<String, String> files = unzip(zip(inputs(true)));
        assertTrue(files.containsKey("shop/connectors/langfuse/application.yaml"));
        assertTrue(files.containsKey("shop/connectors/langfuse/setup.sh"));
        assertTrue(files.containsKey("shop/connectors/valkey/values-aks.yaml"), "cloud overlay chosen at export time");
        String readme = files.get("shop/connectors/README.md");
        assertTrue(readme.indexOf("(`cloudnative-pg`)") < readme.indexOf("(`langfuse`)"), "requires come first:\n" + readme);
        assertTrue(readme.contains("Biome"), "a core requirement is named, not exported");
        assertTrue(files.get("shop/connectors/valkey/application.yaml").contains("$values/connectors/valkey/values-aks.yaml"));
    }

    @Test
    void noSecretMaterial() throws IOException {
        byte[] zip = zip(inputs(true));
        String all = String.join("\n", unzip(zip).values());
        assertFalse(all.contains(WEBHOOK_SECRET), "webhook secret leaked");
        assertFalse(all.contains("sk_live_should_not_leak"), "secret-looking env value leaked");
        assertTrue(all.contains("removed by AIO export"));
        assertTrue(unzip(zip).get("shop/values/values-dev.yaml").contains("value: \"hello\""), "plain env kept");
        // templates carry keys, never values
        for (var e : unzip(zip).entrySet()) {
            if (!e.getKey().startsWith("shop/secrets/")) continue;
            for (Object doc : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(e.getValue())) {
                Map<?, ?> secret = (Map<?, ?>) doc;
                assertEquals("Secret", secret.get("kind"));
                assertNull(secret.get("data"));
                if (secret.get("stringData") instanceof Map<?, ?> sd)
                    sd.values().forEach(v -> assertEquals("", v, e.getKey() + " has a value"));
            }
        }
        String valkey = unzip(zip).get("shop/secrets/shop-valkey.example.yaml");
        assertTrue(valkey.contains("password: \"\""), valkey);
    }

    @Test
    void argoApplicationsAreValid() throws IOException {
        Map<String, String> files = unzip(zip(inputs(true)));
        for (var e : files.entrySet()) {
            if (!(e.getKey().startsWith("shop/argocd/") || e.getKey().endsWith("/application.yaml"))) continue;
            Map<?, ?> app = (Map<?, ?>) yaml(e.getValue());
            assertEquals("argoproj.io/v1alpha1", app.get("apiVersion"), e.getKey());
            assertEquals("Application", app.get("kind"));
            Map<?, ?> meta = (Map<?, ?>) app.get("metadata");
            assertTrue(String.valueOf(meta.get("name")).matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?"));
            Map<?, ?> spec = (Map<?, ?>) app.get("spec");
            assertNotNull(spec.get("destination"));
            assertTrue(e.getValue().contains("REPO_URL"), "points at the owner's repo placeholder");
            assertFalse(e.getValue().contains("aio-gitops"), "no link back to AIO's repository");
        }
        Map<?, ?> prod = (Map<?, ?>) yaml(files.get("shop/argocd/shop-prod.yaml"));
        assertNull(((Map<?, ?>) ((Map<?, ?>) prod.get("spec")).get("syncPolicy")).get("automated"), "prod syncs by hand");
        Map<?, ?> source = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) yaml(files.get("shop/argocd/shop-dev.yaml"))).get("spec")).get("source");
        assertEquals("chart", source.get("path"));
        assertEquals(List.of("values.yaml", "../values/values-dev.yaml"), ((Map<?, ?>) source.get("helm")).get("valueFiles"));
    }

    @Test
    void workflowIsValidAndPinned() throws IOException {
        String wf = unzip(zip(inputs(false))).get("shop/ci/github/workflows/build.yaml");
        Map<?, ?> doc = (Map<?, ?>) yaml(wf);
        Map<?, ?> jobs = (Map<?, ?>) doc.get("jobs");
        assertEquals(List.of("test", "build", "deploy-dev"), new ArrayList<>(jobs.keySet()));
        for (String line : wf.split("\n")) {
            if (!line.trim().startsWith("uses:") && !line.contains(" uses: ")) continue;
            assertTrue(line.matches(".*uses: [A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40} # v[0-9.]+$"), "not pinned: " + line);
        }
        assertTrue(wf.contains("ghcr.io/acme/shop"));
        assertTrue(wf.contains("npm test"));
        assertTrue(wf.contains("trivy-action"));
        assertTrue(wf.contains("severity: \"HIGH,CRITICAL\""));
        assertTrue(wf.contains("yq -i '.image.tag = strenv(TAG)'"));
    }

    @Test
    void redactKeepsReferencesAndPlainValues() {
        String yaml = "env:\n  - name: DB_PASSWORD\n    value: \"x\"\n  - name: MODE\n    value: \"prod\"\n  - name: API_TOKEN\n    value: \"\"\n";
        String out = ExportBundle.redactEnv(yaml);
        assertFalse(out.contains("\"x\""));
        assertTrue(out.contains("value: \"prod\""));
        assertTrue(out.contains("API_TOKEN\n    value: \"\"\n"), "already blank is left alone");
    }

    @Test
    void secretValuesInExtrasAreDetected() {
        assertTrue(ProjectExportService.hasSecretValues("apiVersion: v1\nkind: Secret\nstringData:\n  password: hunter2\n"));
        assertFalse(ProjectExportService.hasSecretValues("apiVersion: v1\nkind: Secret\nstringData:\n  password: \"\"\n"));
        assertFalse(ProjectExportService.hasSecretValues("kind: ConfigMap\ndata:\n  a: b\n"));
    }

    @Test
    void setupScriptsFromHintAndPrefix() throws IOException {
        var c = ConnectorCatalog.parse(new ByteArrayInputStream(CATALOG.getBytes(StandardCharsets.UTF_8))).stream()
                .filter(x -> x.id().equals("langfuse")).findFirst().orElseThrow();
        assertEquals(List.of("langfuse-secrets.sh", "langfuse-z.sh"), ProjectExportService.setupScripts(c,
                java.util.Set.of("setup/langfuse-secrets.sh", "setup/langfuse-z.sh", "setup/milvus-secrets.sh", "values/x.yaml")));
    }

    /** Unzips the bundle and renders the chart with each env's values, when helm is installed. */
    @Test
    void helmTemplateRendersTheExportedChart() throws Exception {
        String helm = findHelm();
        if (helm == null || !Files.isDirectory(Path.of("../../helm/app"))) return;   // not on PATH: skipped
        Path tmp = Files.createTempDirectory("aio-export-test");
        try {
            for (var e : unzip(zip(inputs(false))).entrySet()) {
                Path f = tmp.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue());
            }
            for (String env : List.of("dev", "prod")) {
                Process proc = new ProcessBuilder(helm, "template", "shop", "chart", "-f", "values/values-" + env + ".yaml")
                        .directory(tmp.resolve("shop").toFile()).redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "helm timed out");
                assertEquals(0, proc.exitValue(), "helm template failed for " + env + ":\n" + out);
                assertTrue(out.contains("kind: Deployment"), out);
                assertTrue(out.contains("ghcr.io/acme/shop"), out);
                if ("dev".equals(env) && Files.exists(tmp.resolve("shop/chart/templates/services.yaml"))) {
                    assertTrue(out.contains("kind: Cluster"), "services: manifests rendered\n" + out);
                    assertTrue(out.contains("DATABASE_URL"), out);
                }
            }
        } finally {
            try (Stream<Path> s = Files.walk(tmp)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    /** {@code -Dexport.sample=<dir>} writes a sample bundle there, to read it by eye. */
    @Test
    void writesSampleWhenAsked() throws IOException {
        String dir = System.getProperty("export.sample");
        if (dir == null) return;
        Files.write(Path.of(dir, "shop-aio-export-sample.zip"), zip(inputs(true)));
    }

    private static String findHelm() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator))
            for (String name : List.of("helm", "helm.exe")) {
                Path f = Path.of(dir, name);
                if (Files.isRegularFile(f) && Files.isExecutable(f)) return f.toString();
            }
        return null;
    }
}
