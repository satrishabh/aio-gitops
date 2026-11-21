package com.aio.platform.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The open-source tools AIO offers, from the aio-catalog repository (one file per tool there, built into
 * {@code dist/catalog.yaml}). Sources, in order:
 * <ol>
 *   <li>{@code CONNECTORS_CATALOG}: a local file path or an https URL;</li>
 *   <li>{@code CONNECTORS_REPO} + {@code CONNECTORS_REVISION} on GitHub: {@code dist/catalog.yaml} at that tag;</li>
 *   <li>the built-in core-only list (classpath {@code connectors/catalog.yaml}).</li>
 * </ol>
 * A remote catalog is re-read every 10 minutes. A catalog that fails validation is never used: the last good one
 * stays (graceful degradation), and the error is shown on the Connectors page.
 */
@Component
public class ConnectorCatalog {
    private static final Logger log = LoggerFactory.getLogger(ConnectorCatalog.class);

    public enum Kind { CORE, MANAGED, PLANNED }

    public record Probe(String name, String url, String expect) {}

    public record Connector(String id, String name, String category, String description, String kind, String phase,
                            List<String> clouds,
                            // other connectors (ids) that must be present before this one is enabled
                            List<String> requires,
                            // core: the Deployment that must be available; managed: optional, checked by Test
                            String namespace, String deployment,
                            // managed: chart source and values file (relative to the catalog repo root)
                            String chartRepo, String chart, String version, String previousVersion,
                            String releaseName, String valuesPath,
                            // managed: optional extra values file per cloud / per installation, applied after valuesPath
                            Map<String, String> cloudValues, Map<String, String> siteValues,
                            // managed: optional directory of plain manifests synced with the chart (relative)
                            String extrasPath,
                            List<Probe> probes, String accessHint,
                            // metadata shown on the page: license, governing foundation, project origin, home page
                            String license, String foundation, String origin, String homepage,
                            // single sign-on: mode oidc | proxy | none, clientId for oidc
                            Map<String, String> sso,
                            // how the pinned version's age was verified when the chart index cannot prove it
                            String pinNote) {
        public Kind kindEnum() {
            return Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        }

        public String appName() {
            return "connector-" + id;
        }

        /** Versions an admin may select: the pinned one and the previous one (rollback target). */
        public List<String> versions() {
            List<String> v = new ArrayList<>();
            if (version != null) v.add(version);
            if (previousVersion != null) v.add(previousVersion);
            return v;
        }

        /** Values files: the common one, then the cloud overlay, then the installation (site) overlay. */
        public List<String> valueFiles(String cloud, String site) {
            List<String> files = new ArrayList<>(List.of(valuesPath));
            if (cloudValues != null && cloud != null && cloudValues.containsKey(cloud)) files.add(cloudValues.get(cloud));
            if (siteValues != null && site != null && siteValues.containsKey(site)) files.add(siteValues.get(site));
            return files;
        }

        public List<String> requiresList() {
            return requires == null ? List.of() : requires;
        }

        public List<Probe> probeList() {
            return probes == null ? List.of() : probes;
        }

        public boolean supports(String cloud) {
            return clouds == null || clouds.isEmpty() || clouds.contains("any") || cloud == null
                    || "any".equals(cloud) || clouds.contains(cloud);
        }
    }

    record CatalogFile(List<Connector> connectors) {}

    /** Where the catalog came from and whether the last refresh worked (shown to admins). */
    public record Source(String location, String revision, Instant loadedAt, String lastError, int count) {}

    private static final Pattern ID = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,40}[a-z0-9])?$");
    private static final Pattern K8S_NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern VERSION = Pattern.compile("^v?[0-9]+(\\.[0-9]+){1,2}([-+][0-9A-Za-z.-]+)?$");
    private static final Pattern REL_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*\\.ya?ml$");
    private static final Pattern REL_DIR = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$");
    private static final Pattern REVISION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$");
    private static final Pattern GITHUB_REPO = Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(\\.git)?/?$");
    private static final Set<String> CLOUDS = Set.of("any", "gke", "eks", "aks", "oke", "onprem");
    private static final Set<String> SSO_MODES = Set.of("oidc", "proxy", "none");
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private volatile List<Connector> all;
    private volatile Source source;
    private final String cloud;
    private final String site;
    private final String repo;
    private final String revision;
    private final String location;     // file path or https URL; null = built-in
    private final String githubToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired
    public ConnectorCatalog(@Value("${app.connectors.catalog:}") String catalog,
                            @Value("${app.connectors.cloud:any}") String cloud,
                            @Value("${app.connectors.site:}") String site,
                            @Value("${app.connectors.repo:}") String repo,
                            @Value("${app.connectors.revision:}") String revision,
                            @Value("${app.github.token:}") String githubToken) {
        this.cloud = cloud == null || cloud.isBlank() ? "any" : cloud.trim().toLowerCase(Locale.ROOT);
        if (!CLOUDS.contains(this.cloud)) throw new IllegalStateException("app.connectors.cloud must be one of " + CLOUDS);
        this.site = site == null || site.isBlank() ? null : site.trim();
        require(this.site == null || K8S_NAME.matcher(this.site).matches(), "app.connectors.site must be a short name");
        this.repo = repo == null || repo.isBlank() ? null : repo.trim();
        require(this.repo == null || this.repo.startsWith("https://"), "app.connectors.repo must be an https URL");
        this.revision = revision == null || revision.isBlank() ? null : revision.trim();
        require(this.revision == null || (REVISION.matcher(this.revision).matches() && !this.revision.contains("..")),
                "app.connectors.revision must be a tag, branch or commit");
        this.githubToken = githubToken;
        this.location = resolveLocation(catalog, this.repo, this.revision);
        this.all = List.of();
        refresh();
        if (this.all.isEmpty() && this.location != null) {
            // Never start without a catalog: fall back to the built-in core list until the remote one loads
            this.all = parseBuiltIn();
            log.warn("connector catalog {} not available; using the built-in core list: {}", location, source.lastError());
        }
    }

    /** Tests and tools: a local file (or built-in when empty), no repository. */
    public ConnectorCatalog(String file, String cloud) {
        this(file, cloud, "", "", "", "");
    }

    static String resolveLocation(String catalog, String repo, String revision) {
        if (catalog != null && !catalog.isBlank()) {
            String c = catalog.trim();
            require(!c.startsWith("http://"), "app.connectors.catalog must be a file path or an https URL");
            return c;
        }
        if (repo == null) return null;
        Matcher m = GITHUB_REPO.matcher(repo);
        require(m.matches(), "app.connectors.repo is not a GitHub repository; set CONNECTORS_CATALOG to the catalog URL");
        require(revision != null, "app.connectors.revision (a tag of the catalog repo) is required with app.connectors.repo");
        return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/" + revision + "/dist/catalog.yaml";
    }

    @Scheduled(fixedDelayString = "${app.connectors.refresh-ms:600000}", initialDelayString = "${app.connectors.refresh-ms:600000}")
    public void refresh() {
        try {
            List<Connector> loaded = location == null ? parseBuiltIn() : parse(new ByteArrayInputStream(read(location)));
            this.all = loaded;
            this.source = new Source(location == null ? "built-in (core components only)" : location, revision,
                    Instant.now(), null, loaded.size());
        } catch (RuntimeException | IOException e) {
            String msg = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("connector catalog refresh from {} failed, keeping the last good one: {}", location, msg);
            Source prev = this.source;
            this.source = new Source(location, revision, prev == null ? null : prev.loadedAt(), msg,
                    this.all == null ? 0 : this.all.size());
        }
    }

    private byte[] read(String where) throws IOException {
        if (!where.startsWith("https://")) {
            byte[] b = Files.readAllBytes(Path.of(where));
            require(b.length <= MAX_BYTES, "catalog larger than " + MAX_BYTES + " bytes");
            return b;
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(where)).timeout(Duration.ofSeconds(15)).GET();
        // The token is only ever sent to GitHub's own raw host
        if (githubToken != null && !githubToken.isBlank() && "raw.githubusercontent.com".equals(URI.create(where).getHost()))
            req.header("Authorization", "token " + githubToken);
        try {
            HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            require(res.statusCode() == 200, "HTTP " + res.statusCode() + " from " + where);
            require(res.body().length <= MAX_BYTES, "catalog larger than " + MAX_BYTES + " bytes");
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    public List<Connector> all() {
        return all;
    }

    public String cloud() {
        return cloud;
    }

    /** Installation name selecting siteValues overlays (null = none). */
    public String site() {
        return site;
    }

    /** Repository Argo CD reads values and extras from (null = the GitOps repository). */
    public String valuesRepo() {
        return repo;
    }

    /** Revision of {@link #valuesRepo()} (null = the GitOps branch). */
    public String revision() {
        return revision;
    }

    public Source source() {
        return source;
    }

    public Optional<Connector> find(String id) {
        return all.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    static List<Connector> parseBuiltIn() {
        try (InputStream in = ConnectorCatalog.class.getResourceAsStream("/connectors/catalog.yaml")) {
            if (in == null) throw new IllegalStateException("connectors/catalog.yaml missing from the classpath");
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Connector> parse(InputStream in) {
        Object raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        if (!(raw instanceof Map<?, ?>)) throw new IllegalStateException("connector catalog must be a YAML map");
        CatalogFile f;
        try {
            f = JsonMapper.builderWithJackson2Defaults().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
                    .convertValue(raw, CatalogFile.class);
        } catch (JacksonException e) { // Jackson 2's convertValue threw IllegalArgumentException; keep that contract
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        List<Connector> list = f.connectors() == null ? List.of() : List.copyOf(f.connectors());
        Set<String> ids = new HashSet<>();
        for (Connector c : list) {
            validate(c);
            if (!ids.add(c.id())) throw new IllegalStateException("duplicate connector id " + c.id());
        }
        for (Connector c : list)
            for (String req : c.requiresList())
                require(ids.contains(req) && !req.equals(c.id()), "connector " + c.id() + ": requires unknown connector " + req);
        return list;
    }

    static void validate(Connector c) {
        String where = "connector " + c.id();
        require(c.id() != null && ID.matcher(c.id()).matches(), "invalid connector id " + c.id());
        require(c.name() != null && !c.name().isBlank(), where + ": name is required");
        require(c.category() != null && !c.category().isBlank(), where + ": category is required");
        Kind kind;
        try {
            kind = c.kindEnum();
        } catch (RuntimeException e) {
            throw new IllegalStateException(where + ": kind must be core, managed or planned");
        }
        if (c.clouds() != null) for (String cl : c.clouds())
            require(CLOUDS.contains(cl), where + ": unknown cloud " + cl + " (allowed " + CLOUDS + ")");
        require(c.homepage() == null || c.homepage().startsWith("https://"), where + ": homepage must be https");
        if (c.sso() != null) {
            require(SSO_MODES.contains(c.sso().get("mode")), where + ": sso.mode must be oidc, proxy or none");
            require(c.sso().keySet().stream().allMatch(k -> k.equals("mode") || k.equals("clientId")),
                    where + ": sso may only have mode and clientId");
        }
        switch (kind) {
            case CORE -> {
                require(isName(c.namespace()) && isName(c.deployment()), where + ": core needs namespace and deployment");
            }
            case MANAGED -> {
                require(isName(c.namespace()), where + ": managed needs a namespace");
                require(isName(c.releaseName()), where + ": managed needs a releaseName");
                require(c.chart() != null && K8S_NAME.matcher(c.chart()).matches(), where + ": invalid chart");
                require(c.chartRepo() != null && c.chartRepo().startsWith("https://"), where + ": chartRepo must be https");
                require(c.version() != null && VERSION.matcher(c.version()).matches(), where + ": version must be exact");
                require(c.previousVersion() == null || VERSION.matcher(c.previousVersion()).matches(),
                        where + ": previousVersion must be exact");
                require(isRelYaml(c.valuesPath()), where + ": valuesPath must be a relative .yaml path");
                if (c.cloudValues() != null) c.cloudValues().forEach((cl, path) -> {
                    require(CLOUDS.contains(cl) && !"any".equals(cl), where + ": cloudValues key must be a cloud, not " + cl);
                    require(isRelYaml(path), where + ": cloudValues." + cl + " must be a relative .yaml path");
                });
                if (c.siteValues() != null) c.siteValues().forEach((s, path) -> {
                    require(K8S_NAME.matcher(s).matches(), where + ": siteValues key must be a short name, not " + s);
                    require(isRelYaml(path), where + ": siteValues." + s + " must be a relative .yaml path");
                });
                require(c.extrasPath() == null || (REL_DIR.matcher(c.extrasPath()).matches() && !c.extrasPath().contains("..")),
                        where + ": extrasPath must be a relative directory");
                require(c.deployment() == null || isName(c.deployment()), where + ": invalid deployment");
                for (Probe p : c.probeList()) validateProbe(where, p);
            }
            case PLANNED -> {
            }
        }
    }

    /** Probes may only reach in-cluster services, so a catalog entry cannot turn the platform into a proxy. */
    static void validateProbe(String where, Probe p) {
        require(p.name() != null && !p.name().isBlank(), where + ": probe name is required");
        URI u;
        try {
            u = URI.create(p.url());
        } catch (RuntimeException e) {
            throw new IllegalStateException(where + ": invalid probe url " + p.url());
        }
        String host = u.getHost();
        require(("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && host != null && u.getUserInfo() == null
                        && (host.endsWith(".svc") || host.endsWith(".svc.cluster.local")),
                where + ": probe url must be http(s)://<service>.<namespace>.svc[...]");
    }

    /** Fields of a connector for the API (metadata included). */
    static Map<String, Object> metadata(Connector c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("license", c.license());
        m.put("foundation", c.foundation());
        m.put("origin", c.origin());
        m.put("homepage", c.homepage());
        m.put("sso", c.sso() == null ? null : c.sso().get("mode"));
        return m;
    }

    private static boolean isRelYaml(String p) {
        return p != null && REL_PATH.matcher(p).matches() && !p.contains("..");
    }

    private static boolean isName(String s) {
        return s != null && K8S_NAME.matcher(s).matches();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
