package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads what a project export needs from git and hands it to {@link ExportBundle}.
 *
 * <p>Sources (chosen so the bundle matches what Argo CD deploys, not what this platform image was built with):
 * <ul>
 *   <li>chart: the GitOps repository's {@code app.gitops.chart-path} (helm/app) at {@code app.gitops.branch}, the
 *       exact source of every AIO app Application, read at one resolved commit;</li>
 *   <li>values: {@code apps/<app>/values-<env>.yaml} at the same commit;</li>
 *   <li>connectors: values, cloud overlay, extras and setup scripts from the catalog repository
 *       ({@code CONNECTORS_REPO}) at {@code CONNECTORS_REVISION} (else the GitOps repository at its branch, as
 *       ConnectorService does); the binding blocks from that revision's {@code dist/catalog.yaml}.</li>
 * </ul>
 * Everything is read before the zip is streamed, so a GitHub failure is a normal API error, not a broken download.
 */
@Service
public class ProjectExportService {
    private static final Logger log = LoggerFactory.getLogger(ProjectExportService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final Pattern GITHUB_REPO = Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(\\.git)?/?$");
    private static final Pattern SAFE_REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$");
    private static final Pattern SETUP_REF = Pattern.compile("setup/([A-Za-z0-9._-]+\\.sh)");
    public static final Set<String> CLOUDS = Set.of("any", "gke", "eks", "aks", "oke", "onprem");
    static final int MAX_FILES = 600;
    static final long MAX_BYTES = 25L * 1024 * 1024;

    private final AppProperties props;
    private final GitHubClient github;
    private final RestClient gh;
    private final ConnectorCatalog catalog;
    private final ArgoCdService argo;

    public ProjectExportService(AppProperties props, GitHubClient github, RestClient githubRestClient,
                                ConnectorCatalog catalog, ArgoCdService argo) {
        this.props = props;
        this.github = github;
        this.gh = githubRestClient;
        this.catalog = catalog;
        this.argo = argo;
    }

    /** A repository at a resolved commit, with its file tree (path -> blob sha). */
    record RepoAt(String owner, String repo, String ref, String commit, Map<String, String> blobs) {
        String label() {
            return owner + "/" + repo + "@" + (commit == null ? ref : commit.substring(0, Math.min(12, commit.length())));
        }
    }

    /** What the export dialog lists before downloading (no git reads beyond the values files). */
    public Map<String, Object> preview(Project p) {
        var g = props.gitops();
        Map<String, String> values = new LinkedHashMap<>();
        for (String env : GitOpsService.ENVS) {
            var f = github.getFile(g.owner(), g.repo(), GitOpsService.valuesPath(g, p.name, env), g.branch());
            if (f != null) values.put(env, f.content());
        }
        Map<String, List<String>> used = ExportBundle.usedServices(values);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", ExportBundle.fileName(p, Instant.now()));
        out.put("envs", List.copyOf(values.keySet()));
        out.put("connectors", ExportBundle.withRequires(used.keySet(), id -> catalog.find(id).orElse(null)).stream()
                .map(ConnectorCatalog.Connector::id).toList());
        out.put("installedConnectors", installed().stream().map(ConnectorCatalog.Connector::id).toList());
        return out;
    }

    public ExportBundle.Inputs prepare(Project p, boolean allConnectors, String cloudParam) {
        String cloud = cloudParam == null || cloudParam.isBlank() ? "any" : cloudParam.trim().toLowerCase(Locale.ROOT);
        if (!CLOUDS.contains(cloud)) throw new IllegalArgumentException("cloud must be one of " + new java.util.TreeSet<>(CLOUDS));
        var g = props.gitops();
        List<String> notes = new ArrayList<>();
        Budget budget = new Budget();

        RepoAt gitops = tree(g.owner(), g.repo(), g.branch());
        Map<String, byte[]> chart = new TreeMap<>();
        String chartPrefix = g.chartPath().replaceAll("/+$", "") + "/";
        gitops.blobs().forEach((path, sha) -> {
            if (path.startsWith(chartPrefix)) chart.put(path.substring(chartPrefix.length()), blob(gitops, sha, budget));
        });
        if (chart.isEmpty()) throw new IllegalStateException("chart " + g.chartPath() + " not found in " + gitops.label());

        Map<String, String> values = new LinkedHashMap<>();
        for (String env : GitOpsService.ENVS) {
            String sha = gitops.blobs().get(GitOpsService.valuesPath(g, p.name, env));
            if (sha != null) values.put(env, new String(blob(gitops, sha, budget), StandardCharsets.UTF_8));
        }
        if (values.isEmpty()) throw new IllegalStateException("no values files for " + p.name + " in " + gitops.label());

        Map<String, List<String>> used = ExportBundle.usedServices(values);
        Set<String> ids = new LinkedHashSet<>(used.keySet());
        if (allConnectors) installed().forEach(c -> ids.add(c.id()));
        List<ConnectorCatalog.Connector> wanted = ExportBundle.withRequires(ids, id -> catalog.find(id).orElse(null));
        for (String id : used.keySet())
            if (catalog.find(id).isEmpty()) notes.add("The values use service `" + id + "`, which is not in the AIO catalog any more; it is not exported.");

        List<ExportBundle.ConnectorExport> connectors = new ArrayList<>();
        List<ConnectorCatalog.Connector> missing = new ArrayList<>();
        String catalogSource = "none";
        if (wanted.stream().anyMatch(c -> c.kindEnum() == ConnectorCatalog.Kind.MANAGED)) {
            RepoAt cat = catalogRepo(notes);
            catalogSource = cat == null ? "not available" : cat.label();
            Map<String, Map<String, Object>> bindings = cat == null ? Map.of() : bindings(cat, budget);
            for (ConnectorCatalog.Connector c : wanted) {
                if (c.kindEnum() != ConnectorCatalog.Kind.MANAGED) {
                    missing.add(c);
                    continue;
                }
                connectors.add(connector(c, cat, cloud, bindings.get(c.id()), used.getOrDefault(c.id(), List.of()), budget));
            }
        } else {
            missing.addAll(wanted);
        }
        String chartSource = "from `" + g.chartPath() + "` in " + gitops.label() + " (what Argo CD deploys for AIO apps)";
        return new ExportBundle.Inputs(p, Instant.now(), chartSource, chart, values, connectors, missing, cloud,
                allConnectors, catalogSource, notes);
    }

    /** Managed connectors whose Argo CD Application exists (installed / switched on). */
    List<ConnectorCatalog.Connector> installed() {
        List<ConnectorCatalog.Connector> out = new ArrayList<>();
        for (ConnectorCatalog.Connector c : catalog.all()) {
            if (c.kindEnum() != ConnectorCatalog.Kind.MANAGED || !c.supports(catalog.cloud())) continue;
            try {
                if (argo.getApp(c.appName(), false) != null) out.add(c);
            } catch (RuntimeException e) {
                log.debug("export: cannot read Argo CD app {}", c.appName(), e);
            }
        }
        return out;
    }

    private ExportBundle.ConnectorExport connector(ConnectorCatalog.Connector c, RepoAt cat, String cloud,
                                                   Map<String, Object> binding, List<String> usedIn, Budget budget) {
        List<String> skipped = new ArrayList<>();
        String version = installedVersion(c);
        String values = null, overlay = null;
        Map<String, byte[]> extras = new TreeMap<>();
        Map<String, String> setup = new TreeMap<>();
        if (cat == null) {
            skipped.add("Values, extras and setup were not exported: the catalog repository could not be read.");
        } else {
            values = text(cat, c.valuesPath(), budget);
            if (values == null) skipped.add("Values file " + c.valuesPath() + " not found in the catalog.");
            if (!"any".equals(cloud) && c.cloudValues() != null && c.cloudValues().containsKey(cloud))
                overlay = text(cat, c.cloudValues().get(cloud), budget);
            if (c.siteValues() != null && !c.siteValues().isEmpty())
                skipped.add("Installation overlays (" + String.join(", ", c.siteValues().keySet()) + ") were left out: they hold this installation's host names.");
            if (c.extrasPath() != null) {
                String prefix = c.extrasPath().replaceAll("/+$", "") + "/";
                cat.blobs().forEach((path, sha) -> {
                    if (!path.startsWith(prefix)) return;
                    byte[] bytes = blob(cat, sha, budget);
                    if (hasSecretValues(new String(bytes, StandardCharsets.UTF_8))) {
                        skipped.add("extras/" + path.substring(prefix.length()) + " was left out: it holds Secret values.");
                        return;
                    }
                    extras.put(path.substring(prefix.length()), bytes);
                });
            }
            for (String script : setupScripts(c, cat.blobs().keySet())) {
                String t = text(cat, "setup/" + script, budget);
                if (t != null) setup.put(script, t);
            }
        }
        return new ExportBundle.ConnectorExport(c, version, values, overlay, extras, setup,
                binding, usedIn, skipped);
    }

    /** Setup scripts named in the access hint, plus {@code setup/<id>-*.sh}. */
    static List<String> setupScripts(ConnectorCatalog.Connector c, Set<String> paths) {
        Set<String> out = new java.util.TreeSet<>();
        if (c.accessHint() != null) {
            Matcher m = SETUP_REF.matcher(c.accessHint());
            while (m.find()) if (paths.contains("setup/" + m.group(1))) out.add(m.group(1));
        }
        for (String p : paths)
            if (p.startsWith("setup/" + c.id() + "-") && p.endsWith(".sh") && p.indexOf('/', 6) < 0) out.add(p.substring(6));
        return List.copyOf(out);
    }

    /** True when a YAML text has a Secret with non-empty data / stringData values. */
    static boolean hasSecretValues(String text) {
        try {
            for (Object doc : new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                    new org.yaml.snakeyaml.LoaderOptions())).loadAll(text)) {
                if (!(doc instanceof Map<?, ?> m) || !"Secret".equals(m.get("kind"))) continue;
                for (String k : List.of("data", "stringData"))
                    if (m.get(k) instanceof Map<?, ?> d && d.values().stream().anyMatch(v -> v != null && !String.valueOf(v).isEmpty()))
                        return true;
            }
            return false;
        } catch (RuntimeException e) {
            // not YAML (a script, a JSON dashboard): only a Secret manifest can carry values this check knows about
            return text.contains("kind: Secret") && (text.contains("\ndata:") || text.contains("\nstringData:"));
        }
    }

    private String installedVersion(ConnectorCatalog.Connector c) {
        try {
            Map<String, Object> app = argo.getApp(c.appName(), false);
            if (app != null) {
                List<Map<String, Object>> sources = J.list(app, "spec", "sources");
                Object first = sources.isEmpty() ? J.get(app, "spec", "source") : sources.get(0);
                String v = J.str(first, "targetRevision");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (RuntimeException e) {
            log.debug("export: cannot read installed version of {}", c.id(), e);
        }
        return c.version();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> bindings(RepoAt cat, Budget budget) {
        String text = text(cat, "dist/catalog.yaml", budget);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (text == null) return out;
        if (ExportBundle.parseMap(text).get("connectors") instanceof List<?> list)
            for (Object o : list)
                if (o instanceof Map<?, ?> m && m.get("binding") instanceof Map<?, ?> b)
                    out.put(String.valueOf(m.get("id")), (Map<String, Object>) b);
        return out;
    }

    private RepoAt catalogRepo(List<String> notes) {
        var g = props.gitops();
        String owner = g.owner(), repo = g.repo(), ref = g.branch();
        if (catalog.valuesRepo() != null) {
            Matcher m = GITHUB_REPO.matcher(catalog.valuesRepo());
            if (!m.matches()) {
                notes.add("The connector catalog repository is not on GitHub; connector values were not exported.");
                return null;
            }
            owner = m.group(1);
            repo = m.group(2);
            ref = catalog.revision() != null ? catalog.revision() : ref;
        } else if (catalog.revision() != null) {
            ref = catalog.revision();
        }
        try {
            return tree(owner, repo, ref);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("export: catalog repository {}/{}@{} not readable: {}", owner, repo, ref, e.getMessage());
            notes.add("The connector catalog (" + owner + "/" + repo + "@" + ref + ") could not be read; connector values were not exported.");
            return null;
        }
    }

    // ------------------------------------------------------------------ GitHub reads

    @SuppressWarnings("unchecked")
    RepoAt tree(String owner, String repo, String ref) {
        for (String s : new String[]{owner, repo})
            if (!s.matches("[A-Za-z0-9_.-]+") || s.contains("..")) throw new IllegalArgumentException("unsafe repository " + s);
        if (!SAFE_REF.matcher(ref).matches() || ref.contains("..")) throw new IllegalArgumentException("unsafe ref " + ref);
        String commit = github.resolveCommit(owner, repo, ref);
        Map<String, Object> body = gh.get().uri("/repos/" + owner + "/" + repo + "/git/trees/" + commit + "?recursive=1")
                .retrieve().body(MAP);
        if (body == null) throw new IllegalStateException("empty tree for " + owner + "/" + repo + "@" + ref);
        if (Boolean.TRUE.equals(body.get("truncated")))
            log.warn("export: tree of {}/{}@{} is truncated; some files may be missing", owner, repo, ref);
        Map<String, String> blobs = new TreeMap<>();
        if (body.get("tree") instanceof List<?> entries)
            for (Object e : entries)
                if (e instanceof Map<?, ?> m && "blob".equals(m.get("type")))
                    blobs.put(String.valueOf(m.get("path")), String.valueOf(m.get("sha")));
        return new RepoAt(owner, repo, ref, commit, blobs);
    }

    byte[] blob(RepoAt at, String sha, Budget budget) {
        if (!sha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("unsafe blob sha");
        Map<String, Object> body = gh.get().uri("/repos/" + at.owner() + "/" + at.repo() + "/git/blobs/" + sha)
                .retrieve().body(MAP);
        String content = body == null ? "" : String.valueOf(body.get("content")).replaceAll("\\s", "");
        byte[] bytes = "base64".equals(body == null ? null : body.get("encoding"))
                ? Base64.getDecoder().decode(content) : content.getBytes(StandardCharsets.UTF_8);
        budget.add(bytes.length);
        return bytes;
    }

    private String text(RepoAt at, String path, Budget budget) {
        String sha = at.blobs().get(path);
        return sha == null ? null : new String(blob(at, sha, budget), StandardCharsets.UTF_8);
    }

    /** Bounds one export (files and bytes), so a huge extras folder cannot exhaust memory. */
    static final class Budget {
        int files;
        long bytes;

        void add(long n) {
            files++;
            bytes += n;
            if (files > MAX_FILES || bytes > MAX_BYTES)
                throw new IllegalStateException("export is larger than " + MAX_FILES + " files / " + (MAX_BYTES >> 20) + " MiB");
        }
    }
}
