package com.aio.platform.service;

import com.aio.platform.domain.Project;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Builds the "leave AIO with your project" zip (docs/architecture/PROJECT-SERVICES.md, section 5) from inputs that
 * {@link ProjectExportService} has already read from git. Pure: no network, so it is unit tested directly.
 *
 * <p>Layout, rooted at {@code <app>/} (meant to become the root of the owner's own deploy repository):
 * {@code README.md}, {@code chart/}, {@code values/values-<env>.yaml}, {@code argocd/<app>-<env>.yaml},
 * {@code ci/github/workflows/build.yaml}, {@code connectors/<id>/...}, {@code connectors/README.md},
 * {@code secrets/<name>.example.yaml}.
 *
 * <p>Never written: Secret values, tokens, the webhook secret. Values files keep what is in git, except literal
 * values of env vars whose names look secret (PASSWORD, TOKEN, ...), which are blanked as a precaution.
 */
public final class ExportBundle {
    private ExportBundle() {}

    public static final String REPO_URL = "REPO_URL";
    /** Same builder image (and pin) as tekton/tasks/build-image.yaml, so buildpacks builds match. */
    static final String PACK_BUILDER = "docker.io/paketobuildpacks/ubuntu-noble-builder:0.0.203";

    /**
     * GitHub Actions pinned by full commit SHA (checked with {@code gh api repos/<repo>/commits/<tag>} on 2026-11-21).
     * Bump deliberately; the comment after each {@code uses:} names the release.
     */
    static final Map<String, String[]> ACTIONS = Map.of(
            "checkout", new String[]{"actions/checkout", "3d3c42e5aac5ba805825da76410c181273ba90b1", "v7.0.1"},
            "buildx", new String[]{"docker/setup-buildx-action", "f87e5991a6d7451dcb8d9637bfbc97413f497069", "v4.4.1"},
            "login", new String[]{"docker/login-action", "8aa5831fb8d48d8f76594c7ddde61ae58019f550", "v4.8.0"},
            "build-push", new String[]{"docker/build-push-action", "c3c9e263c25d99ce0380d002d59b67737d91b0dc", "v7.4.0"},
            "trivy", new String[]{"aquasecurity/trivy-action", "ed142fd0673e97e23eac54620cfb913e5ce36c25", "v0.36.0"},
            "pack", new String[]{"buildpacks/github-actions/setup-pack", "0f33674a6e9aaddbdce74e58983bc1812871453e", "v6.2.4"});

    /** One connector as it goes into connectors/<id>/. */
    public record ConnectorExport(ConnectorCatalog.Connector connector, String version,
                                  String values, String cloudOverlay,
                                  Map<String, byte[]> extras, Map<String, String> setupScripts,
                                  Map<String, Object> binding, List<String> usedInEnvs, List<String> skipped) {}

    /**
     * Everything read from git. {@code values}: env -> values file (missing env = no file); {@code chart}: path
     * relative to the chart root -> bytes; {@code catalogConnectors}: every catalog entry (for requires that are not
     * exported, e.g. core components).
     */
    public record Inputs(Project project, Instant at, String chartSource, Map<String, byte[]> chart,
                         Map<String, String> values, List<ConnectorExport> connectors,
                         List<ConnectorCatalog.Connector> missingRequires, String cloud, boolean allConnectors,
                         String catalogSource, List<String> notes) {}

    // ------------------------------------------------------------------ zip

    public static String fileName(Project p, Instant at) {
        return p.name + "-aio-export-" + DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(at) + ".zip";
    }

    /** Every entry of the bundle, path (rooted at {@code <app>/}) -> bytes, in a stable order. */
    public static Map<String, byte[]> entries(Inputs in) {
        Project p = in.project();
        String root = p.name + "/";
        Map<String, byte[]> out = new LinkedHashMap<>();
        Map<String, String> values = redactedValues(in.values());
        List<SecretTemplate> secrets = secretTemplates(p, values, in.connectors());

        out.put(root + "README.md", utf8(readme(in, values, secrets)));
        new TreeMap<>(in.chart()).forEach((path, bytes) -> out.put(root + "chart/" + path, bytes));
        values.forEach((env, yaml) -> out.put(root + "values/values-" + env + ".yaml", utf8(yaml)));
        for (String env : values.keySet())
            out.put(root + "argocd/" + p.name + "-" + env + ".yaml", utf8(appApplication(p, env)));
        out.put(root + "ci/github/workflows/build.yaml", utf8(workflow(p, values.get("dev"))));
        for (ConnectorExport c : installOrder(in.connectors())) {
            String dir = root + "connectors/" + c.connector().id() + "/";
            out.put(dir + "application.yaml", utf8(connectorApplication(c, in.cloud())));
            out.put(dir + "values.yaml", utf8(c.values() == null ? "{}\n" : c.values()));
            if (c.cloudOverlay() != null) out.put(dir + "values-" + in.cloud() + ".yaml", utf8(c.cloudOverlay()));
            new TreeMap<>(c.extras()).forEach((path, bytes) -> out.put(dir + "extras/" + path, bytes));
            if (c.setupScripts().size() == 1)
                out.put(dir + "setup.sh", utf8(c.setupScripts().values().iterator().next()));
            else new TreeMap<>(c.setupScripts()).forEach((name, text) -> out.put(dir + "setup/" + name, utf8(text)));
        }
        if (!in.connectors().isEmpty() || !in.missingRequires().isEmpty())
            out.put(root + "connectors/README.md", utf8(connectorsReadme(in)));
        for (SecretTemplate s : secrets) out.put(root + "secrets/" + s.name() + ".example.yaml", utf8(s.yaml()));
        return out;
    }

    public static void write(Inputs in, OutputStream os) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(os)) {
            long time = in.at().toEpochMilli();
            for (var e : entries(in).entrySet()) {
                ZipEntry z = new ZipEntry(e.getKey());
                z.setTime(time);
                zip.putNextEntry(z);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------------ values

    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(PASSWORD|PASSWD|SECRET|TOKEN|API_?KEY|PRIVATE_?KEY|CREDENTIAL|ACCESS_?KEY).*");
    /** The env: entries RuntimeConfig writes: {@code - name: X} followed by {@code value: "..."}. */
    private static final Pattern ENV_ITEM = Pattern.compile("(?m)^(\\s*-\\s*name:\\s*[\"']?)([A-Za-z_][A-Za-z0-9_]*)([\"']?\\s*\\R\\s+value:\\s*)(.*)$");

    static Map<String, String> redactedValues(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((env, yaml) -> out.put(env, redactEnv(yaml)));
        return out;
    }

    /** Blanks literal values of env vars that look secret; a valueFrom reference is left alone. */
    static String redactEnv(String yaml) {
        if (yaml == null) return null;
        Matcher m = ENV_ITEM.matcher(yaml);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String value = m.group(4).trim();
            boolean blank = value.isEmpty() || value.equals("\"\"") || value.equals("''");
            String repl = SECRET_NAME.matcher(m.group(2)).matches() && !blank
                    ? m.group(1) + m.group(2) + m.group(3) + "\"\"   # removed by AIO export: set it from a Secret (see README)"
                    : m.group(0);
            m.appendReplacement(b, Matcher.quoteReplacement(repl));
        }
        m.appendTail(b);
        return b.toString();
    }

    static Map<String, Object> parseMap(String yaml) {
        if (yaml == null || yaml.isBlank()) return Map.of();
        try {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            if (!(o instanceof Map<?, ?> m)) return Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Connector ids under {@code services:} in each env's values file -> the envs that use them. */
    public static Map<String, List<String>> usedServices(Map<String, String> values) {
        Map<String, List<String>> used = new TreeMap<>();
        values.forEach((env, yaml) -> {
            if (parseMap(yaml).get("services") instanceof Map<?, ?> services)
                for (Object id : services.keySet()) used.computeIfAbsent(String.valueOf(id), k -> new ArrayList<>()).add(env);
        });
        return used;
    }

    /** {@code ids} plus everything they require, transitively; unknown ids are dropped. */
    public static List<ConnectorCatalog.Connector> withRequires(Collection<String> ids,
                                                                Function<String, ConnectorCatalog.Connector> find) {
        Map<String, ConnectorCatalog.Connector> seen = new LinkedHashMap<>();
        List<String> queue = new ArrayList<>(ids);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (seen.containsKey(id)) continue;
            ConnectorCatalog.Connector c = find.apply(id);
            if (c == null) continue;
            seen.put(id, c);
            queue.addAll(c.requiresList());
        }
        return List.copyOf(seen.values());
    }

    /** Requires first, then by id; a cycle cannot hang it (the rest follow in id order). */
    static List<ConnectorExport> installOrder(List<ConnectorExport> list) {
        Map<String, ConnectorExport> left = new TreeMap<>();
        for (ConnectorExport c : list) left.put(c.connector().id(), c);
        List<ConnectorExport> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        while (!left.isEmpty()) {
            ConnectorExport next = left.values().stream()
                    .filter(c -> c.connector().requiresList().stream().allMatch(r -> done.contains(r) || !left.containsKey(r)))
                    .findFirst().orElse(left.values().iterator().next());
            out.add(next);
            done.add(next.connector().id());
            left.remove(next.connector().id());
        }
        return out;
    }

    // ------------------------------------------------------------------ secrets

    record SecretTemplate(String name, String yaml, String purpose) {}

    /**
     * Every Secret the app or its services expect: image pull secrets, envFrom secrets, secretKeyRef targets.
     * Secrets an operator creates for an {@code instance} service (CloudNativePG's {@code <name>-app}) are not
     * templated; the README says so.
     */
    static List<SecretTemplate> secretTemplates(Project p, Map<String, String> values, List<ConnectorExport> connectors) {
        Map<String, Map<String, Object>> bindings = new LinkedHashMap<>();
        for (ConnectorExport c : connectors) if (c.binding() != null) bindings.put(c.connector().id(), c.binding());
        // name -> (keys, envs, type, purpose)
        Map<String, TreeSet<String>> keys = new TreeMap<>();
        Map<String, TreeSet<String>> envs = new TreeMap<>();
        Map<String, String> types = new TreeMap<>();
        Map<String, String> purposes = new TreeMap<>();
        for (var e : values.entrySet()) {
            String env = e.getKey();
            Map<String, Object> v = parseMap(e.getValue());
            for (Object ps : list(v.get("imagePullSecrets"))) {
                String name = str(ps, "name");
                if (name == null) continue;
                types.put(name, "kubernetes.io/dockerconfigjson");
                keys.computeIfAbsent(name, k -> new TreeSet<>()).add(".dockerconfigjson");
                envs.computeIfAbsent(name, k -> new TreeSet<>()).add(env);
                purposes.put(name, "registry login used to pull the app image");
            }
            for (Object ef : list(v.get("envFrom"))) {
                String name = ef instanceof Map<?, ?> m ? str(m.get("secretRef"), "name") : null;
                if (name == null) continue;
                keys.computeIfAbsent(name, k -> new TreeSet<>());
                envs.computeIfAbsent(name, k -> new TreeSet<>()).add(env);
                purposes.putIfAbsent(name, "loaded into the app as environment variables (envFrom)");
            }
            collectRefs(list(v.get("env")), env, "app env", keys, envs, purposes);
            if (v.get("services") instanceof Map<?, ?> services) for (var s : services.entrySet()) {
                String id = String.valueOf(s.getKey());
                Map<String, Object> binding = bindings.get(id);
                if (binding != null && "instance".equals(binding.get("scope"))) continue;   // created by the operator
                if (s.getValue() instanceof Map<?, ?> svc)
                    collectRefs(list(svc.get("env")), env, "service " + id, keys, envs, purposes);
            }
        }
        // shared bindings: the copy the platform makes, even when no env var references it by that name
        for (ConnectorExport c : connectors) {
            Map<String, Object> b = c.binding();
            if (b == null || c.usedInEnvs().isEmpty() || !(b.get("copySecret") instanceof Map<?, ?> copy)) continue;
            String name = substitute(String.valueOf(copy.get("name")), p.name, c.connector().id());
            if (name.contains("${")) continue;
            if (copy.get("keys") instanceof Map<?, ?> km) for (Object k : km.values())
                keys.computeIfAbsent(name, x -> new TreeSet<>()).add(String.valueOf(k));
            envs.computeIfAbsent(name, x -> new TreeSet<>()).addAll(c.usedInEnvs());
            purposes.put(name, "credentials of the shared " + c.connector().name() + " (copied from Secret "
                    + copy.get("from") + " in namespace " + c.connector().namespace() + ")");
        }
        List<SecretTemplate> out = new ArrayList<>();
        for (String name : keys.keySet()) {
            if (!K8S_NAME.matcher(name).matches()) continue;
            String type = types.getOrDefault(name, "Opaque");
            StringBuilder y = new StringBuilder();
            y.append("# Template only: AIO never exports secret values. Fill in the values and apply it in each\n")
                    .append("# namespace listed below, or create the Secret with your secret manager instead.\n")
                    .append("# Used for: ").append(purposes.getOrDefault(name, "the app")).append('\n');
            if ("kubernetes.io/dockerconfigjson".equals(type))
                y.append("# Easiest: kubectl -n <namespace> create secret docker-registry ").append(name)
                        .append(" --docker-server=<registry> --docker-username=<user> --docker-password=<token>\n");
            if (keys.get(name).isEmpty()) y.append("# Add the keys your app reads under stringData.\n");
            boolean first = true;
            for (String env : envs.get(name)) {
                if (!first) y.append("---\n");
                first = false;
                y.append("apiVersion: v1\nkind: Secret\nmetadata:\n  name: ").append(name)
                        .append("\n  namespace: ").append(namespace(env))
                        .append("\ntype: ").append(type).append("\nstringData:");
                if (keys.get(name).isEmpty()) y.append(" {}\n");
                else {
                    y.append('\n');
                    for (String k : keys.get(name)) y.append("  ").append(yamlKey(k)).append(": \"\"\n");
                }
            }
            out.add(new SecretTemplate(name, y.toString(), purposes.getOrDefault(name, "the app")));
        }
        return out;
    }

    /** Namespace of an env in the export: the same as on AIO (one namespace per environment). */
    static String namespace(String env) {
        return env;
    }

    private static final Pattern K8S_NAME = Pattern.compile("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$");

    private static void collectRefs(List<Object> env, String envName, String purpose, Map<String, TreeSet<String>> keys,
                                    Map<String, TreeSet<String>> envs, Map<String, String> purposes) {
        for (Object item : env) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("valueFrom") instanceof Map<?, ?> vf)) continue;
            Object ref = vf.get("secretKeyRef");
            String name = str(ref, "name"), key = str(ref, "key");
            if (name == null) continue;
            TreeSet<String> k = keys.computeIfAbsent(name, x -> new TreeSet<>());
            if (key != null) k.add(key);
            envs.computeIfAbsent(name, x -> new TreeSet<>()).add(envName);
            purposes.putIfAbsent(name, purpose);
        }
    }

    /** Secret names that operators create for instance services (listed in the README, no template). */
    static Set<String> operatorSecrets(Map<String, String> values, List<ConnectorExport> connectors) {
        Set<String> instance = new TreeSet<>();
        for (ConnectorExport c : connectors)
            if (c.binding() != null && "instance".equals(c.binding().get("scope"))) instance.add(c.connector().id());
        Set<String> out = new TreeSet<>();
        for (String yaml : values.values())
            if (parseMap(yaml).get("services") instanceof Map<?, ?> services) for (var s : services.entrySet())
                if (instance.contains(String.valueOf(s.getKey())) && s.getValue() instanceof Map<?, ?> svc)
                    for (Object item : list(svc.get("env")))
                        if (item instanceof Map<?, ?> m && m.get("valueFrom") instanceof Map<?, ?> vf) {
                            String n = str(vf.get("secretKeyRef"), "name");
                            if (n != null) out.add(n + " (" + s.getKey() + ")");
                        }
        return out;
    }

    static String substitute(String template, String app, String id) {
        return template.replace("${name}", app + "-" + id).replace("${app}", app);
    }

    // ------------------------------------------------------------------ Argo CD

    static String appApplication(Project p, String env) {
        Map<String, Object> helm = new LinkedHashMap<>();
        helm.put("releaseName", p.name);
        helm.put("valueFiles", List.of("values.yaml", "../values/values-" + env + ".yaml"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("repoURL", REPO_URL);
        source.put("targetRevision", "main");
        source.put("path", "chart");
        source.put("helm", helm);
        Map<String, Object> syncPolicy = new LinkedHashMap<>();
        // prod is synced by hand, as on AIO; dev and staging follow git
        if (!"prod".equals(env)) syncPolicy.put("automated", Map.of("prune", true, "selfHeal", true));
        syncPolicy.put("syncOptions", List.of("CreateNamespace=true"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("project", "default");
        spec.put("source", source);
        spec.put("destination", Map.of("server", "https://kubernetes.default.svc", "namespace", namespace(env)));
        spec.put("syncPolicy", syncPolicy);
        return "# Argo CD Application for " + p.name + " " + env + ". Replace REPO_URL with your deploy repository\n"
                + "# (the folder this file is in is its root) and targetRevision with its branch.\n"
                + dump(application(p.name + "-" + env, Map.of("app.kubernetes.io/part-of", p.name, "env", env), spec));
    }

    static String connectorApplication(ConnectorExport x, String cloud) {
        ConnectorCatalog.Connector c = x.connector();
        String dir = "connectors/" + c.id() + "/";
        List<String> valueFiles = new ArrayList<>(List.of("$values/" + dir + "values.yaml"));
        if (x.cloudOverlay() != null) valueFiles.add("$values/" + dir + "values-" + cloud + ".yaml");
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("repoURL", c.chartRepo());
        chart.put("chart", c.chart());
        chart.put("targetRevision", x.version());
        chart.put("helm", Map.of("releaseName", c.releaseName(), "valueFiles", valueFiles));
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(chart);
        sources.add(Map.of("repoURL", REPO_URL, "targetRevision", "main", "ref", "values"));
        if (!x.extras().isEmpty()) sources.add(Map.of("repoURL", REPO_URL, "targetRevision", "main", "path", dir + "extras"));
        Map<String, Object> syncPolicy = new LinkedHashMap<>();
        syncPolicy.put("automated", Map.of("prune", true, "selfHeal", true));
        syncPolicy.put("syncOptions", List.of("CreateNamespace=true", "ServerSideApply=true"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("project", "default");
        spec.put("sources", sources);
        spec.put("destination", Map.of("server", "https://kubernetes.default.svc", "namespace", c.namespace()));
        spec.put("syncPolicy", syncPolicy);
        return "# " + c.name() + " " + x.version() + " (pinned by the AIO catalog). Replace REPO_URL with your deploy repository.\n"
                + dump(application(c.id(), Map.of("aio.export/connector", c.id()), spec));
    }

    private static Map<String, Object> application(String name, Map<String, String> labels, Map<String, Object> spec) {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("apiVersion", "argoproj.io/v1alpha1");
        app.put("kind", "Application");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("namespace", "argocd");
        meta.put("labels", labels);
        app.put("metadata", meta);
        app.put("spec", spec);
        return app;
    }

    static String dump(Object o) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndicatorIndent(2);
        opts.setIndentWithIndicator(true);
        opts.setWidth(120);
        return new Yaml(new Representer(opts), opts).dump(o);
    }

    // ------------------------------------------------------------------ GitHub Actions

    static String uses(String key) {
        String[] a = ACTIONS.get(key);
        return a[0] + "@" + a[1] + " # " + a[2];
    }

    /** JSON string = valid YAML double-quoted scalar. */
    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : (s == null ? "" : s).toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return b.append('"').toString();
    }

    static String imageRepository(String valuesDev) {
        Object image = parseMap(valuesDev).get("image");
        String repo = str(image, "repository");
        return repo == null || repo.isBlank() ? "REGISTRY/OWNER/APP" : repo;
    }

    static String workflow(Project p, String valuesDev) {
        String image = imageRepository(valuesDev);
        String registry = image.contains("/") && image.substring(0, image.indexOf('/')).matches(".*[.:].*")
                ? image.substring(0, image.indexOf('/')) : "docker.io";
        boolean ghcr = "ghcr.io".equals(registry);
        String strategy = p.buildStrategy == null || p.buildStrategy.isBlank() ? "auto" : p.buildStrategy.toLowerCase(Locale.ROOT);
        String sev = p.scanSeverity == null ? "HIGH,CRITICAL" : p.scanSeverity;
        boolean gate = !"NONE".equalsIgnoreCase(sev);
        String branch = p.defaultBranch == null || p.defaultBranch.isBlank() ? "main" : p.defaultBranch;
        boolean test = p.testCommand != null && !p.testCommand.isBlank();
        String ctx = p.contextDir == null || p.contextDir.isBlank() ? "." : p.contextDir;
        String df = p.dockerfile == null || p.dockerfile.isBlank() ? "Dockerfile" : p.dockerfile;

        StringBuilder w = new StringBuilder();
        w.append("# Generated by the AIO export for ").append(p.name).append(". It replaces the AIO Tekton pipeline.\n")
                .append("# Copy it to .github/workflows/build.yaml in the application repository")
                .append(p.repoOwner == null ? "" : " (" + p.repoOwner + "/" + p.repoName + ")").append(".\n")
                .append("# Secrets to add in that repository: DEPLOY_REPO_TOKEN (push access to the deploy repository)")
                .append(ghcr ? "" : ", REGISTRY_USERNAME, REGISTRY_PASSWORD").append(".\n")
                .append("# Actions are pinned by full commit SHA; the comment names the release.\n")
                .append("name: build\n")
                .append("on:\n  push:\n    branches: [").append(q(branch)).append("]\n  workflow_dispatch: {}\n")
                .append("permissions:\n  contents: read\n")
                .append("env:\n")
                .append("  IMAGE: ").append(q(image)).append("              # your registry and repository\n")
                .append("  CONTEXT: ").append(q(ctx)).append('\n')
                .append("  DOCKERFILE: ").append(q(df)).append('\n')
                .append("  BUILD_STRATEGY: ").append(q(strategy)).append("   # auto | dockerfile | buildpacks (buildah builds the Dockerfile)\n")
                .append("  DEPLOY_REPO: \"OWNER/DEPLOY_REPO\"   # the repository holding this bundle (REPO_URL)\n")
                .append("  VALUES_FILE: \"values/values-dev.yaml\"\n")
                .append("jobs:\n");
        if (test) {
            w.append("  test:\n    runs-on: ubuntu-24.04\n");
            if (p.testImage != null && !p.testImage.isBlank()) w.append("    container:\n      image: ").append(q(p.testImage)).append('\n');
            w.append("    steps:\n")
                    .append("      - uses: ").append(uses("checkout")).append("\n        with:\n          persist-credentials: false\n")
                    .append("      - name: Test\n        working-directory: ${{ env.CONTEXT }}\n        run: |\n");
            for (String line : p.testCommand.split("\\R")) w.append("          ").append(line).append('\n');
        }
        w.append("  build:\n");
        if (test) w.append("    needs: [test]\n");
        w.append("    runs-on: ubuntu-24.04\n    permissions:\n      contents: read\n      packages: write\n")
                .append("    outputs:\n      tag: ${{ steps.meta.outputs.tag }}\n")
                .append("    steps:\n")
                .append("      - uses: ").append(uses("checkout")).append("\n        with:\n          persist-credentials: false\n")
                .append("      - id: meta\n        name: Tag and strategy\n        run: |\n")
                .append("          echo \"tag=${GITHUB_SHA::12}\" >> \"$GITHUB_OUTPUT\"\n")
                .append("          s=\"$BUILD_STRATEGY\"\n")
                .append("          if [ \"$s\" = auto ]; then if [ -f \"$CONTEXT/$DOCKERFILE\" ]; then s=dockerfile; else s=buildpacks; fi; fi\n")
                .append("          if [ \"$s\" = buildah ]; then s=dockerfile; fi\n")
                .append("          echo \"strategy=$s\" >> \"$GITHUB_OUTPUT\"\n")
                .append("      - uses: ").append(uses("login")).append("\n        with:\n")
                .append("          registry: ").append(q(registry)).append('\n')
                .append(ghcr ? "          username: ${{ github.actor }}\n          password: ${{ secrets.GITHUB_TOKEN }}\n"
                        : "          username: ${{ secrets.REGISTRY_USERNAME }}\n          password: ${{ secrets.REGISTRY_PASSWORD }}\n")
                .append("      - if: steps.meta.outputs.strategy == 'dockerfile'\n        uses: ").append(uses("buildx")).append('\n')
                .append("      - if: steps.meta.outputs.strategy == 'dockerfile'\n        uses: ").append(uses("build-push")).append('\n')
                .append("        with:\n          context: ${{ env.CONTEXT }}\n          file: ${{ env.CONTEXT }}/${{ env.DOCKERFILE }}\n")
                .append("          load: true\n          push: false\n          tags: ${{ env.IMAGE }}:${{ steps.meta.outputs.tag }}\n")
                .append("      - if: steps.meta.outputs.strategy == 'buildpacks'\n        uses: ").append(uses("pack")).append('\n')
                .append("      - if: steps.meta.outputs.strategy == 'buildpacks'\n        name: Build with Cloud Native Buildpacks\n")
                .append("        run: pack build \"$IMAGE:${{ steps.meta.outputs.tag }}\" --path \"$CONTEXT\" --builder ")
                .append(PACK_BUILDER).append('\n')
                .append("      - name: Trivy scan\n        uses: ").append(uses("trivy")).append('\n')
                .append("        with:\n          image-ref: ${{ env.IMAGE }}:${{ steps.meta.outputs.tag }}\n")
                .append("          severity: ").append(q(gate ? sev : "HIGH,CRITICAL")).append('\n')
                .append("          ignore-unfixed: true\n          exit-code: ").append(q(gate ? "1" : "0")).append('\n')
                .append("      - name: Push\n        run: docker push \"$IMAGE:${{ steps.meta.outputs.tag }}\"\n")
                .append("  deploy-dev:\n    needs: [build]\n    runs-on: ubuntu-24.04\n")
                .append("    concurrency: deploy-repo\n    steps:\n")
                .append("      - uses: ").append(uses("checkout")).append("\n        with:\n")
                .append("          repository: ${{ env.DEPLOY_REPO }}\n          token: ${{ secrets.DEPLOY_REPO_TOKEN }}\n")
                .append("      - name: Bump the dev image tag\n        env:\n          TAG: ${{ needs.build.outputs.tag }}\n        run: |\n")
                .append("          yq -i '.image.tag = strenv(TAG)' \"$VALUES_FILE\"\n")
                .append("          git config user.name \"github-actions[bot]\"\n")
                .append("          git config user.email \"41898282+github-actions[bot]@users.noreply.github.com\"\n")
                .append("          git commit -am \"").append(p.name).append(": deploy $TAG to dev\" || exit 0\n")
                .append("          git push\n");
        return w.toString();
    }

    // ------------------------------------------------------------------ READMEs

    static String readme(Inputs in, Map<String, String> values, List<SecretTemplate> secrets) {
        Project p = in.project();
        StringBuilder r = new StringBuilder();
        r.append("# ").append(p.name).append(" without AIO\n\n")
                .append("Exported from AIO on ").append(DateTimeFormatter.ISO_INSTANT.format(in.at())).append(". ")
                .append("This folder runs the app on any Kubernetes cluster with Helm, or with Argo CD from your own git repository.\n\n")
                .append("Nothing secret is in it: no Secret values, tokens or webhook secrets. ")
                .append("Create the Secrets yourself from the templates in `secrets/`.\n\n");
        r.append("## What is here\n\n")
                .append("| Path | What it is |\n| --- | --- |\n")
                .append("| `chart/` | The app Helm chart, ").append(in.chartSource()).append(" |\n")
                .append("| `values/values-<env>.yaml` | Each environment's values as they are in git now (image, ingress, resources, `services:`) |\n")
                .append("| `argocd/` | One Argo CD Application per environment; replace `REPO_URL` |\n")
                .append("| `ci/github/workflows/build.yaml` | GitHub Actions workflow replacing the AIO pipeline: test, build, Trivy scan, push, bump the dev tag |\n");
        if (!in.connectors().isEmpty())
            r.append("| `connectors/` | ").append(in.allConnectors() ? "Every connector switched on in AIO" : "The connectors the app uses, plus what they require")
                    .append(", pinned as in the AIO catalog; install order in `connectors/README.md` |\n");
        if (!secrets.isEmpty()) r.append("| `secrets/` | Secret templates (keys, no values) |\n");
        r.append('\n');

        r.append("## Prerequisites\n\n")
                .append("- A Kubernetes cluster and `kubectl` access; Helm 3, or Argo CD for the GitOps way.\n")
                .append("- An ingress controller (the values use `ingress.className`, default `nginx`).\n")
                .append("- cert-manager with a ClusterIssuer if `ingress.tls` is true (the values name it in `ingress.issuer`).\n")
                .append("- A container registry you can push to; edit `image.repository` in the values and `IMAGE` in the workflow.\n");
        boolean spot = values.values().stream().anyMatch(v -> v != null && v.contains("\nspot:"));
        if (spot) r.append("- The values tolerate spot nodes (`spot:`); set `spot.enabled: false` if your cluster has none.\n");
        r.append('\n');

        r.append("## Install order\n\n");
        int step = 1;
        if (!in.connectors().isEmpty())
            r.append(step++).append(". Connectors, in the order in `connectors/README.md` (run each `setup.sh` first where there is one).\n");
        if (!secrets.isEmpty())
            r.append(step++).append(". Secrets: fill in and apply each `secrets/*.example.yaml` (namespaces: one per environment).\n");
        r.append(step++).append(". The app, per environment. With Helm:\n\n```sh\n");
        for (String env : values.keySet())
            r.append("helm upgrade --install ").append(p.name).append(" chart -n ").append(namespace(env))
                    .append(" --create-namespace -f values/values-").append(env).append(".yaml\n");
        r.append("```\n\n   Or with Argo CD: push this folder to a git repository, replace `REPO_URL` in `argocd/*.yaml`")
                .append(in.connectors().isEmpty() ? "" : " and `connectors/*/application.yaml`")
                .append(", then `kubectl apply -n argocd -f argocd/`.\n")
                .append(step).append(". CI: copy `ci/github/workflows/build.yaml` to `.github/workflows/` in the application repository")
                .append(" and set `DEPLOY_REPO` and the secrets it names. Each push to the default branch then builds, scans,")
                .append(" pushes and bumps `values/values-dev.yaml`; promote to staging and prod by copying the tag.\n\n");

        if (!secrets.isEmpty()) {
            r.append("## Secrets to create\n\n| Secret | Used for |\n| --- | --- |\n");
            for (SecretTemplate s : secrets) r.append("| `").append(s.name()).append("` | ").append(s.purpose()).append(" |\n");
            r.append('\n');
        }
        Set<String> operator = operatorSecrets(values, in.connectors());
        if (!operator.isEmpty()) {
            r.append("Created by the service's operator, nothing to do: ");
            r.append(String.join(", ", operator.stream().map(s -> "`" + s.replaceFirst(" \\(", "` (")).toList())).append(".\n\n");
        }
        boolean redacted = values.values().stream().anyMatch(v -> v != null && v.contains("# removed by AIO export"));
        if (redacted)
            r.append("Some env vars looked secret and their values were removed from the values files (marked `# removed by AIO export`). ")
                    .append("Put them in a Secret and reference it with `envFrom` or `valueFrom.secretKeyRef`.\n\n");

        r.append("## URLs\n\n");
        boolean any = false;
        for (var e : values.entrySet()) {
            Object ingress = parseMap(e.getValue()).get("ingress");
            String host = str(ingress, "host");
            if (host == null || host.isBlank()) continue;
            any = true;
            boolean tls = ingress instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("tls"));
            r.append("- ").append(e.getKey()).append(": ").append(tls ? "https://" : "http://").append(host)
                    .append(" (point the name at your ingress, or change `ingress.host`)\n");
        }
        if (!any) r.append("No ingress host in the values.\n");
        r.append('\n');

        Map<String, List<String>> used = usedServices(values);
        if (!used.isEmpty()) {
            r.append("## Services\n\nThe `services:` block in each values file is rendered by the chart (the app's own database or Kafka,")
                    .append(" env vars, pod annotations). It needs these connectors on the cluster:\n\n");
            used.forEach((id, envs) -> {
                ConnectorExport c = in.connectors().stream().filter(x -> x.connector().id().equals(id)).findFirst().orElse(null);
                r.append("- `").append(id).append("` (").append(String.join(", ", envs)).append(")");
                String note = c == null || c.binding() == null ? null : (String) c.binding().get("exportNote");
                if (note != null && !note.isBlank()) r.append(": ").append(note.trim());
                r.append('\n');
            });
            r.append('\n');
        }
        if (!in.notes().isEmpty()) {
            r.append("## Notes from the export\n\n");
            for (String n : in.notes()) r.append("- ").append(n).append('\n');
            r.append('\n');
        }
        return r.toString();
    }

    static String connectorsReadme(Inputs in) {
        StringBuilder r = new StringBuilder("# Connectors\n\n");
        r.append(in.allConnectors() ? "Every connector switched on in AIO" : "The connectors this app uses and what they require")
                .append(", at the chart versions AIO pinned. Values, extras and setup scripts are from the AIO catalog (")
                .append(in.catalogSource()).append(")")
                .append("any".equals(in.cloud()) ? "." : ", with the `" + in.cloud() + "` cloud overlay.")
                .append("\n\nInstall in this order (each one's requirements come first). With Argo CD: replace `REPO_URL` in")
                .append(" `application.yaml`, then `kubectl apply -n argocd -f connectors/<id>/application.yaml`.")
                .append(" Without Argo CD: `helm repo add`, then `helm upgrade --install` with the same chart, version and values files.\n\n");
        int i = 1;
        for (ConnectorExport x : installOrder(in.connectors())) {
            ConnectorCatalog.Connector c = x.connector();
            r.append(i++).append(". **").append(c.name()).append("** (`").append(c.id()).append("`): chart `").append(c.chart())
                    .append("` ").append(x.version()).append(" from ").append(c.chartRepo()).append(", namespace `")
                    .append(c.namespace()).append("`");
            if (!c.requiresList().isEmpty()) r.append("; requires ").append(String.join(", ", c.requiresList()));
            if (!x.usedInEnvs().isEmpty()) r.append("; used by the app in ").append(String.join(", ", x.usedInEnvs()));
            r.append(".\n");
            if (!x.setupScripts().isEmpty())
                r.append("   - Setup: run ").append(x.setupScripts().size() == 1 ? "`setup.sh`"
                        : String.join(", ", x.setupScripts().keySet().stream().map(s -> "`setup/" + s + "`").toList()))
                        .append(" (from the catalog; read it first, some create cloud resources).\n");
            String note = x.binding() == null ? null : (String) x.binding().get("exportNote");
            if (note != null && !note.isBlank()) r.append("   - ").append(note.trim()).append('\n');
            if (c.accessHint() != null && !c.accessHint().isBlank())
                r.append("   - ").append(c.accessHint().trim().replaceAll("\\s+", " ")).append('\n');
            for (String s : x.skipped()) r.append("   - ").append(s).append('\n');
        }
        if (!in.missingRequires().isEmpty()) {
            r.append("\nAlso needed, not exported (core components of AIO, install them your own way):\n\n");
            for (ConnectorCatalog.Connector c : in.missingRequires())
                r.append("- ").append(c.name()).append(" (`").append(c.id()).append("`)").append(c.homepage() == null ? "" : ": " + c.homepage()).append('\n');
        }
        return r.toString();
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String str(Object map, String key) {
        if (!(map instanceof Map<?, ?> m)) return null;
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String yamlKey(String k) {
        return k.matches("[A-Za-z0-9_.-]+") && !k.startsWith(".") ? k : q(k);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
