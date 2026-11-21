package com.aio.platform.service;

import com.aio.platform.domain.AttachedService;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.AttachedServiceRepo;
import com.aio.platform.service.ConnectorCatalog.Binding;
import com.aio.platform.service.ConnectorCatalog.BindingEnv;
import com.aio.platform.service.ConnectorCatalog.BindingOption;
import com.aio.platform.service.ConnectorCatalog.Connector;
import com.aio.platform.service.ConnectorCatalog.Scope;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Project -> Services: a switched-on connector used by a project in one environment, as described by the connector's
 * {@code binding} in the catalog (docs/architecture/PROJECT-SERVICES.md). Attaching writes {@code services.<id>} into
 * the env's values file (one commit), copies the connector's Secret into the env namespace for scope shared, and
 * records a row; detaching undoes all three. Only for environments on the platform's own cluster, where connectors run.
 */
@Service
public class ProjectServicesService {
    private static final Logger log = LoggerFactory.getLogger(ProjectServicesService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern K8S_NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,251}[a-z0-9])?$");
    static final String LABEL_MANAGED = "aio.platform/managed";
    static final String LABEL_PROJECT = "aio.platform/project";
    static final String LABEL_SERVICE = "aio.platform/service";
    static final String LABEL_ENV = "aio.platform/env";

    /** Kubernetes Secrets the service touches; a seam so tests need no fabric8 mocks. */
    interface Secrets {
        Secret get(String namespace, String name);

        void apply(Secret secret);

        void delete(String namespace, String name);
    }

    private final ConnectorCatalog catalog;
    private final ConnectorService connectors;
    private final ClusterRegistry clusters;
    private final GitOpsService gitops;
    private final AttachedServiceRepo repo;
    private final ServiceUsage usage;
    private final AuditService audit;
    Secrets secrets;

    public ProjectServicesService(ConnectorCatalog catalog, ConnectorService connectors, ClusterRegistry clusters,
                                  GitOpsService gitops, AttachedServiceRepo repo, ServiceUsage usage, AuditService audit,
                                  KubernetesClient k8s) {
        this.catalog = catalog;
        this.connectors = connectors;
        this.clusters = clusters;
        this.gitops = gitops;
        this.repo = repo;
        this.usage = usage;
        this.audit = audit;
        this.secrets = new Secrets() {
            public Secret get(String ns, String name) {
                return k8s.secrets().inNamespace(ns).withName(name).get();
            }

            public void apply(Secret s) {
                k8s.secrets().inNamespace(s.getMetadata().getNamespace()).resource(s).forceConflicts().serverSideApply();
            }

            public void delete(String ns, String name) {
                k8s.secrets().inNamespace(ns).withName(name).delete();
            }
        };
    }

    // ------------------------------------------------------------------ overview

    /** GET /api/projects/{id}/services: see "API shapes" in docs/architecture/PROJECT-SERVICES.md. */
    public Map<String, Object> overview(Project p) {
        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, Object>> inGit = new LinkedHashMap<>();
        for (String env : GitOpsService.ENVS) {
            try {
                inGit.put(env, ServicesValues.read(gitops.readRaw(p, env).yaml()));
            } catch (RuntimeException e) {
                warnings.add("values-" + env + ".yaml could not be read: " + e.getMessage());
            }
        }
        List<Map<String, Object>> envs = new ArrayList<>();
        for (String env : GitOpsService.ENVS) {
            var c = clusters.clusterFor(env);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("env", env);
            m.put("cluster", c.name());
            m.put("platformCluster", clusters.inCluster(c));
            m.put("namespace", ClusterService.namespace(p, env));
            envs.add(m);
        }
        List<AttachedService> rows = repo.findByProjectIdOrderByConnectorIdAscEnvAsc(p.id);
        List<Map<String, Object>> available = new ArrayList<>();
        for (Connector c : catalog.all()) {
            if (c.binding() == null || !c.supports(catalog.cloud())) continue;
            available.add(availability(p, c, rows, inGit));
        }
        List<Map<String, Object>> attached = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (AttachedService row : rows) {
            seen.add(row.connectorId + "/" + row.env);
            Map<String, Object> block = inGit.containsKey(row.env) ? asMap(inGit.get(row.env).get(row.connectorId)) : null;
            attached.add(view(row.connectorId, row.env, row, block, inGit.containsKey(row.env)));
        }
        inGit.forEach((env, services) -> services.forEach((id, block) -> {
            if (seen.add(id + "/" + env)) attached.add(view(id, env, null, asMap(block), true));
        }));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("envs", envs);
        out.put("available", available);
        out.put("attached", attached);
        out.put("warnings", warnings);
        return out;
    }

    private Map<String, Object> availability(Project p, Connector c, List<AttachedService> rows,
                                             Map<String, Map<String, Object>> inGit) {
        Binding b = c.binding();
        Map<String, Object> d = connectors.describe(c);
        Object status = d.get("status");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connector", c.id());
        m.put("name", c.name());
        m.put("label", b.label());
        m.put("summary", b.summary());
        m.put("scope", b.scopeEnum().name().toLowerCase(java.util.Locale.ROOT));
        m.put("deletesData", b.scopeEnum() == Scope.INSTANCE);
        m.put("status", String.valueOf(status));
        m.put("statusMessage", d.get("message"));
        m.put("usable", usable(status));
        m.put("options", b.optionList().stream().map(o -> {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("id", o.id());
            om.put("label", o.label() == null ? o.id() : o.label());
            om.put("default", o.defaultValue());
            om.put("choices", o.choiceList());
            return om;
        }).toList());
        m.put("envVars", b.envVars().stream().map(BindingEnv::name).toList());
        m.put("podAnnotations", List.copyOf(b.annotations().keySet()));
        m.put("exportNote", b.exportNote());
        Map<String, Object> perEnv = new LinkedHashMap<>();
        for (String env : GitOpsService.ENVS) {
            boolean attached = rows.stream().anyMatch(r -> r.connectorId.equals(c.id()) && r.env.equals(env)
                    && AttachedService.ATTACHED.equals(r.status))
                    || (inGit.containsKey(env) && inGit.get(env).containsKey(c.id()));
            String[] reason = attached ? null : unavailable(c, env, status);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("state", attached ? "attached" : reason == null ? "available" : "unavailable");
            e.put("reason", reason == null ? null : reason[0]);
            e.put("message", reason == null ? null : reason[1]);
            perEnv.put(env, e);
        }
        m.put("envs", perEnv);
        return m;
    }

    private static boolean usable(Object status) {
        return status == ConnectorService.Status.HEALTHY || status == ConnectorService.Status.PROGRESSING;
    }

    /** {code, message} why the connector cannot be attached in env, or null when it can. */
    private String[] unavailable(Connector c, String env, Object status) {
        Binding b = c.binding();
        if (!b.envList().contains(env))
            return new String[] {"ENV_NOT_ALLOWED", b.label() + " is for " + String.join(", ", b.envList()) + " only"};
        var cluster = clusters.clusterFor(env);
        if (!clusters.inCluster(cluster))
            return new String[] {"OTHER_CLUSTER", "not available on " + cluster.name()
                    + ": connectors run on the platform's own cluster"};
        if (status == ConnectorService.Status.NOT_INSTALLED || status == ConnectorService.Status.OFF)
            return new String[] {"NOT_INSTALLED", c.name() + " is not switched on; an admin switches it on first (Connectors)"};
        if (!usable(status))
            return new String[] {"NOT_HEALTHY", c.name() + " is " + String.valueOf(status).toLowerCase(java.util.Locale.ROOT)
                    .replace('_', ' ') + "; ask an admin to check it on the Connectors page"};
        return null;
    }

    private Map<String, Object> view(String connectorId, String env, AttachedService row, Map<String, Object> block,
                                     boolean gitRead) {
        Connector c = catalog.find(connectorId).orElse(null);
        Binding b = c == null ? null : c.binding();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connector", connectorId);
        m.put("name", c == null ? connectorId : c.name());
        m.put("label", b == null ? connectorId : b.label());
        m.put("scope", b == null ? null : b.scopeEnum().name().toLowerCase(java.util.Locale.ROOT));
        m.put("env", env);
        m.put("options", row == null ? Map.of() : readOptions(row.options));
        String status;
        if (row != null && AttachedService.FAILED.equals(row.status)) status = "FAILED";
        else if (row == null) status = "UNTRACKED";             // in git, not attached through AIO (hand edit)
        else if (gitRead && block == null) status = "NOT_IN_GIT"; // attached, but the block was removed by hand
        else status = "ATTACHED";
        m.put("status", status);
        m.put("inGit", gitRead ? block != null : null);
        m.put("deletesData", block != null ? block.containsKey("manifests") : b != null && b.scopeEnum() == Scope.INSTANCE);
        m.put("envVars", block == null ? List.of() : envNames(block));
        m.put("detail", row == null ? null : row.detail);
        m.put("createdBy", row == null ? null : row.createdBy);
        m.put("createdAt", row == null ? null : row.createdAt);
        return m;
    }

    private static List<String> envNames(Map<String, Object> block) {
        return block.get("env") instanceof List<?> l
                ? l.stream().map(e -> e instanceof Map<?, ?> em ? String.valueOf(em.get("name")) : null)
                .filter(java.util.Objects::nonNull).toList()
                : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    static Map<String, String> readOptions(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, new TypeReference<TreeMap<String, String>>() {});
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ attach / detach

    public record AttachRequest(String connector, String env, Map<String, String> options) {}

    /** Checks, copies the Secret (shared), writes services.<id> (one commit), records the row and audits. */
    public Map<String, Object> attach(Project p, AttachRequest req, String actor) {
        if (req == null || req.connector() == null || req.connector().isBlank()) throw new IllegalArgumentException("choose a connector");
        Connector c = catalog.find(req.connector())
                .orElseThrow(() -> new IllegalArgumentException("Unknown connector " + req.connector()));
        Binding b = c.binding();
        if (b == null) throw new IllegalArgumentException(c.name() + " cannot be used by projects");
        String env = req.env();
        if (env == null || !GitOpsService.ENVS.contains(env)) throw new IllegalArgumentException("choose dev, staging or prod");
        if (!c.supports(catalog.cloud())) throw new IllegalArgumentException(c.name() + " is not supported on " + catalog.cloud());
        String[] reason = unavailable(c, env, connectors.describe(c).get("status"));
        if (reason != null) {
            if ("ENV_NOT_ALLOWED".equals(reason[0])) throw new IllegalArgumentException(reason[1]);
            throw new IllegalStateException(reason[1]);
        }
        Map<String, String> options = resolveOptions(b, req.options());
        Map<String, String> vars = variables(p, env, c, options);
        Map<String, Object> block = render(c, vars);

        AttachedService row = repo.findByProjectIdAndConnectorIdAndEnv(p.id, c.id(), env).orElseGet(AttachedService::new);
        boolean update = row.id != null && AttachedService.ATTACHED.equals(row.status);
        row.projectId = p.id;
        row.connectorId = c.id();
        row.env = env;
        row.options = JSON.writeValueAsString(options);
        row.createdBy = actor;
        row.createdAt = java.time.Instant.now();
        String target = p.name + "/" + env;
        try {
            if (b.copySecret() != null) row.copiedSecret = copySecret(p, env, c, vars);
            writeServices(p, env, services -> services.put(c.id(), block),
                    "services: attach " + c.id() + " to " + p.name + " " + env);
        } catch (RuntimeException e) {
            row.status = AttachedService.FAILED;
            row.detail = e.getMessage();
            repo.save(row);
            audit.record(actor, "project.service-attach", target, c.id() + " failed: " + e.getMessage());
            throw e;
        }
        row.status = AttachedService.ATTACHED;
        row.detail = null;
        row = repo.save(row);
        audit.record(actor, "project.service-attach", target, c.id() + (update ? " (updated)" : "")
                + (options.isEmpty() ? "" : " " + options));
        return view(c.id(), env, row, block, true);
    }

    /** Result of a detach: what was removed, and Secrets an operator left behind (delete them once Argo CD pruned). */
    public record DetachResult(String connector, String env, boolean dataDeleted, List<String> leftBehind) {}

    /**
     * Removes services.<id> (commit), the copied Secret and the row. An instance service's data goes with its
     * manifests when Argo CD prunes them, so that needs the project name typed as confirmation.
     */
    public DetachResult detach(Project p, String connectorId, String env, String confirm, String actor) {
        if (env == null || !GitOpsService.ENVS.contains(env)) throw new IllegalArgumentException("env must be dev, staging or prod");
        Connector c = catalog.find(connectorId).orElse(null);
        AttachedService row = repo.findByProjectIdAndConnectorIdAndEnv(p.id, connectorId, env).orElse(null);
        Map<String, Object> block = asMap(ServicesValues.read(gitops.readRaw(p, env).yaml()).get(connectorId));
        if (block == null && row == null) throw new NoSuchElementException();
        boolean deletesData = (block != null && block.containsKey("manifests"))
                || (c != null && c.binding() != null && c.binding().scopeEnum() == Scope.INSTANCE);
        if (deletesData && !p.name.equals(confirm))
            throw new IllegalArgumentException("Removing " + connectorId + " deletes its data in " + env
                    + "; type the project name to confirm");
        if (block != null)
            writeServices(p, env, services -> services.remove(connectorId),
                    "services: detach " + connectorId + " from " + p.name + " " + env);
        List<String> warnings = new ArrayList<>();
        String copied = row != null && row.copiedSecret != null ? row.copiedSecret
                : c != null && c.binding() != null && c.binding().copySecret() != null
                        && clusters.inCluster(clusters.clusterFor(env))
                ? substitute(c.binding().copySecret().name(), variables(p, env, c, Map.of()), "copySecret.name") : null;
        if (copied != null) deleteCopy(p, env, connectorId, copied, warnings);
        List<String> leftBehind = deletesData && c != null && c.binding() != null ? leftBehind(p, env, c) : List.of();
        if (row != null) repo.delete(row);
        audit.record(actor, "project.service-detach", p.name + "/" + env, connectorId
                + (deletesData ? " (data deleted when Argo CD prunes)" : "")
                + (leftBehind.isEmpty() ? "" : "; left behind: " + String.join(", ", leftBehind))
                + (warnings.isEmpty() ? "" : "; " + String.join("; ", warnings)));
        return new DetachResult(connectorId, env, deletesData, leftBehind);
    }

    /**
     * Secrets the instance's env vars read that its operator created without an owner reference (OpenSearch's
     * {@code <name>-admin-password}, cert-manager's CA Secret), so pruning the manifests leaves them. They are not
     * deleted here: until Argo CD prunes the cluster (prod has no auto-sync) the running instance still needs them.
     */
    List<String> leftBehind(Project p, String env, Connector c) {
        if (!clusters.inCluster(clusters.clusterFor(env))) return List.of();
        Map<String, String> vars = variables(p, env, c, defaults(c.binding()));
        String ns = ClusterService.namespace(p, env);
        Set<String> names = new java.util.LinkedHashSet<>();
        for (BindingEnv e : c.binding().envVars()) {
            if (e.secret() == null) continue;
            String name = substitute(e.secret(), vars, "env " + e.name());
            if (name.startsWith(vars.get("name"))) names.add(name);   // the instance's own Secrets only
        }
        List<String> out = new ArrayList<>();
        for (String name : names) {
            try {
                Secret s = secrets.get(ns, name);
                if (s != null && (s.getMetadata().getOwnerReferences() == null || s.getMetadata().getOwnerReferences().isEmpty()))
                    out.add(ns + "/" + name);
            } catch (RuntimeException e) {
                log.debug("left-behind check of {}/{} failed: {}", ns, name, e.getMessage());
            }
        }
        return out;
    }

    /** Removes the copied Secrets of a project being deleted (the rows go with the project row). */
    public List<String> deleteProjectSecrets(Project p) {
        List<String> warnings = new ArrayList<>();
        for (AttachedService row : repo.findByProjectIdOrderByConnectorIdAscEnvAsc(p.id))
            if (row.copiedSecret != null) deleteCopy(p, row.env, row.connectorId, row.copiedSecret, warnings);
        return warnings;
    }

    public List<ServiceUsage.Use> usage(String connectorId) {
        return usage.of(connectorId);
    }

    // ------------------------------------------------------------------ git

    private void writeServices(Project p, String env, java.util.function.Consumer<Map<String, Object>> change, String message) {
        for (int attempt = 1; ; attempt++) {
            var raw = gitops.readRaw(p, env);
            Map<String, Object> services = ServicesValues.read(raw.yaml());
            change.accept(services);
            String updated = ServicesValues.write(raw.yaml(), services);
            try {
                gitops.writeRaw(p, env, updated, raw.sha(), message);
                return;
            } catch (IllegalStateException e) {
                // someone committed the file in between (deploy, promotion): re-read and apply again
                if (attempt >= 3 || e.getMessage() == null || !e.getMessage().contains("changed since")) throw e;
            }
        }
    }

    // ------------------------------------------------------------------ secrets (scope shared)

    /** Copies the connector's Secret into the env namespace, labelled so detach and project delete find it. */
    String copySecret(Project p, String env, Connector c, Map<String, String> vars) {
        var cs = c.binding().copySecret();
        String name = substitute(cs.name(), vars, "copySecret.name");
        if (!K8S_NAME.matcher(name).matches()) throw new IllegalArgumentException("copySecret.name " + name + " is not a valid Secret name");
        Secret source = secrets.get(c.namespace(), cs.from());
        if (source == null)
            throw new IllegalStateException(c.name() + ": Secret " + c.namespace() + "/" + cs.from()
                    + " not found (see the connector's setup on the Connectors page)");
        Map<String, String> data = new TreeMap<>();
        cs.keys().forEach((from, to) -> {
            String v = source.getData() == null ? null : source.getData().get(from);
            if (v == null) throw new IllegalStateException(c.name() + ": Secret " + c.namespace() + "/" + cs.from() + " has no key " + from);
            data.put(to, v);
        });
        String ns = ClusterService.namespace(p, env);
        Secret existing = secrets.get(ns, name);
        if (existing != null && !ownedCopy(existing, p, c.id()))
            throw new IllegalStateException("Secret " + ns + "/" + name + " already exists and was not created by AIO for "
                    + p.name + "; rename or remove it first");
        secrets.apply(new SecretBuilder().withNewMetadata().withName(name).withNamespace(ns)
                .addToLabels(LABEL_MANAGED, "true").addToLabels(LABEL_PROJECT, p.name)
                .addToLabels(LABEL_SERVICE, c.id()).addToLabels(LABEL_ENV, env)
                .addToAnnotations("aio.platform/copied-from", c.namespace() + "/" + cs.from())
                .endMetadata().withType("Opaque").withData(data).build());
        return name;
    }

    static boolean ownedCopy(Secret s, Project p, String connectorId) {
        Map<String, String> l = s.getMetadata() == null ? null : s.getMetadata().getLabels();
        return l != null && "true".equals(l.get(LABEL_MANAGED)) && p.name.equals(l.get(LABEL_PROJECT))
                && connectorId.equals(l.get(LABEL_SERVICE));
    }

    private void deleteCopy(Project p, String env, String connectorId, String name, List<String> warnings) {
        String ns = ClusterService.namespace(p, env);
        try {
            Secret s = secrets.get(ns, name);
            if (s == null) return;
            if (!ownedCopy(s, p, connectorId)) {
                warnings.add("Secret " + ns + "/" + name + " is not AIO's copy; left in place");
                return;
            }
            secrets.delete(ns, name);
        } catch (RuntimeException e) {
            warnings.add("Secret " + ns + "/" + name + " not deleted: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ options, placeholders, rendering (pure)

    /** Request options checked against the binding: known ids only, choices strictly; missing ones take the default. */
    static Map<String, String> resolveOptions(Binding b, Map<String, String> requested) {
        Map<String, String> in = requested == null ? Map.of() : requested;
        Set<String> ids = new java.util.HashSet<>();
        b.optionList().forEach(o -> ids.add(o.id()));
        for (String k : in.keySet()) if (!ids.contains(k)) throw new IllegalArgumentException("unknown option " + k);
        Map<String, String> out = new TreeMap<>();
        for (BindingOption o : b.optionList()) {
            String v = in.get(o.id());
            if (v == null || v.isBlank()) v = o.defaultValue();
            String label = o.label() == null ? o.id() : o.label();
            if (v == null) throw new IllegalArgumentException(label + " is required");
            v = v.trim();
            if (!o.choiceList().isEmpty() ? !o.choiceList().contains(v) : !ConnectorCatalog.validOptionValue(v))
                throw new IllegalArgumentException(label + " must be one of " + o.choiceList());
            out.put(o.id(), v);
        }
        return out;
    }

    private static Map<String, String> defaults(Binding b) {
        Map<String, String> out = new TreeMap<>();
        for (BindingOption o : b.optionList()) if (o.defaultValue() != null) out.put(o.id(), o.defaultValue());
        return out;
    }

    /** ${app} ${env} ${namespace} ${name} ${opt.<id>} */
    static Map<String, String> variables(Project p, String env, Connector c, Map<String, String> options) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("app", p.name);
        vars.put("env", env);
        vars.put("namespace", ClusterService.namespace(p, env));
        vars.put("name", p.name + "-" + c.id());
        options.forEach((k, v) -> vars.put("opt." + k, v));
        return vars;
    }

    /**
     * Plain string substitution of {@code ${...}}; an unknown placeholder is an error. Kubernetes {@code $(VAR)}
     * references (env var expansion) are not placeholders and pass through.
     */
    static String substitute(String text, Map<String, String> vars, String where) {
        if (text == null) return null;
        Matcher m = ConnectorCatalog.PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (v == null) throw new IllegalArgumentException("unknown placeholder ${" + m.group(1) + "} in " + where);
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The services.<id> block for the values file: manifests (substituted, checked), env in catalog order, podAnnotations. */
    static Map<String, Object> render(Connector c, Map<String, String> vars) {
        Binding b = c.binding();
        Map<String, Object> block = new LinkedHashMap<>();
        if (b.manifestsText() != null) block.put("manifests", manifests(substitute(b.manifestsText(), vars, "manifests"), c.id()));
        List<Object> env = new ArrayList<>();
        for (BindingEnv e : b.envVars()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", e.name());
            if (e.value() != null) item.put("value", substitute(e.value(), vars, "env " + e.name()));
            else {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("name", substitute(e.secret(), vars, "env " + e.name()));
                ref.put("key", e.key());
                item.put("valueFrom", Map.of("secretKeyRef", ref));
            }
            env.add(item);
        }
        if (!env.isEmpty()) block.put("env", env);
        if (!b.annotations().isEmpty()) {
            Map<String, Object> ann = new TreeMap<>();
            b.annotations().forEach((k, v) -> ann.put(k, substitute(v, vars, "podAnnotations " + k)));
            block.put("podAnnotations", ann);
        }
        return block;
    }

    /** Normalised manifests text; every document must be a mapping with apiVersion, kind and metadata.name. */
    static String manifests(String text, String connectorId) {
        String t = text.replace("\r\n", "\n").strip() + "\n";
        int docs = 0;
        try {
            for (Object doc : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(t)) {
                if (doc == null) continue;
                if (!(doc instanceof Map<?, ?> m) || m.get("apiVersion") == null || m.get("kind") == null
                        || J.str(m, "metadata", "name") == null)
                    throw new IllegalArgumentException("binding manifests of " + connectorId
                            + ": every document needs apiVersion, kind and metadata.name");
                docs++;
            }
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new IllegalArgumentException("binding manifests of " + connectorId + " are not valid YAML: "
                    + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        }
        if (docs == 0) throw new IllegalArgumentException("binding manifests of " + connectorId + " are empty");
        return t;
    }
}
