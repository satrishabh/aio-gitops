package com.aio.platform.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The open-source tools AIO knows about, loaded from a YAML catalog so any tool with a Helm chart can be added
 * without code changes (classpath {@code connectors/catalog.yaml}, or the file named by {@code CONNECTORS_CATALOG}).
 * See that file for the format. The catalog is validated strictly at startup: a typo fails fast instead of
 * producing a connector that half works.
 */
@Component
public class ConnectorCatalog {
    public enum Kind { CORE, MANAGED, PLANNED }

    public record Probe(String name, String url, String expect) {}

    public record Connector(String id, String name, String category, String description, String kind, String phase,
                            List<String> clouds,
                            // other connectors (ids) that must be present before this one is enabled
                            List<String> requires,
                            // core: the Deployment that must be available; managed: optional, checked by Test
                            String namespace, String deployment,
                            // managed: chart source and values file (relative to the GitOps repo root)
                            String chartRepo, String chart, String version, String previousVersion,
                            String releaseName, String valuesPath,
                            // managed: optional directory of plain manifests synced with the chart (relative)
                            String extrasPath,
                            List<Probe> probes, String accessHint) {
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

    private static final Pattern ID = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,40}[a-z0-9])?$");
    private static final Pattern K8S_NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern VERSION = Pattern.compile("^v?[0-9]+(\\.[0-9]+){1,2}([-+][0-9A-Za-z.-]+)?$");
    private static final Pattern REL_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*\\.ya?ml$");
    private static final Pattern REL_DIR = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$");
    private static final Set<String> CLOUDS = Set.of("any", "gke", "eks", "aks", "oke", "onprem");

    private final List<Connector> all;
    private final String cloud;

    public ConnectorCatalog(@Value("${app.connectors.catalog:}") String file,
                            @Value("${app.connectors.cloud:any}") String cloud) {
        this.all = load(file);
        this.cloud = cloud == null || cloud.isBlank() ? "any" : cloud.trim().toLowerCase(Locale.ROOT);
        if (!CLOUDS.contains(this.cloud)) throw new IllegalStateException("app.connectors.cloud must be one of " + CLOUDS);
    }

    public List<Connector> all() {
        return all;
    }

    public String cloud() {
        return cloud;
    }

    public Optional<Connector> find(String id) {
        return all.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    static List<Connector> load(String file) {
        try (InputStream in = file == null || file.isBlank()
                ? ConnectorCatalog.class.getResourceAsStream("/connectors/catalog.yaml")
                : Files.newInputStream(Path.of(file))) {
            if (in == null) throw new IllegalStateException("connectors/catalog.yaml missing from the classpath");
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read connector catalog " + file, e);
        }
    }

    static List<Connector> parse(InputStream in) {
        Object raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        if (!(raw instanceof Map<?, ?>)) throw new IllegalStateException("connector catalog must be a YAML map");
        CatalogFile f = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .convertValue(raw, CatalogFile.class);
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
                require(c.valuesPath() != null && REL_PATH.matcher(c.valuesPath()).matches() && !c.valuesPath().contains(".."),
                        where + ": valuesPath must be a relative .yaml path");
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

    private static boolean isName(String s) {
        return s != null && K8S_NAME.matcher(s).matches();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
