package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.service.ConnectorCatalog.Connector;
import com.aio.platform.service.ConnectorCatalog.Probe;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Status, health tests and lifecycle (enable / disable / roll back) of open-source connectors.
 * Every lookup fails soft: an unreachable tool or a missing permission shows up as a status on that one
 * connector and never breaks the list (graceful degradation).
 */
@Service
public class ConnectorService {
    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);
    static final String PROJECT = "connectors";
    static final String HUB = "https://kubernetes.default.svc";
    static final String NS_LABEL = "aio.platform/connector";

    /**
     * Fields other controllers own, so Argo CD neither reports them as drift nor reverts them (found in the live AKS
     * cycles): aggregated ClusterRole rules (kube-green), and CA bundles that operators or cert-manager inject into
     * webhooks, APIServices and CRD conversion webhooks (KEDA rotates its own certificates).
     */
    /**
     * AKS's admissions enforcer adds namespaceSelector rules to every webhook so webhooks never block AKS system
     * namespaces. Charts usually set an empty selector, so without this Argo CD and AKS overwrite each other forever
     * (seen live with KEDA: the webhook generation kept climbing). Trade-off: a selector change in a new chart
     * version is not applied on upgrade (RespectIgnoreDifferences); it is on a fresh install.
     */
    static final String AKS_WEBHOOK_SELECTORS = ".webhooks[]?.namespaceSelector";

    static final List<Map<String, Object>> IGNORE_DIFFERENCES = List.of(
            Map.of("group", "rbac.authorization.k8s.io", "kind", "ClusterRole",
                    "jqPathExpressions", List.of("select(.aggregationRule != null) | .rules")),
            Map.of("group", "admissionregistration.k8s.io", "kind", "ValidatingWebhookConfiguration",
                    "jqPathExpressions", List.of(".webhooks[]?.clientConfig.caBundle", AKS_WEBHOOK_SELECTORS)),
            Map.of("group", "admissionregistration.k8s.io", "kind", "MutatingWebhookConfiguration",
                    "jqPathExpressions", List.of(".webhooks[]?.clientConfig.caBundle", AKS_WEBHOOK_SELECTORS)),
            Map.of("group", "apiregistration.k8s.io", "kind", "APIService", "jsonPointers", List.of("/spec/caBundle")),
            Map.of("group", "apiextensions.k8s.io", "kind", "CustomResourceDefinition",
                    "jqPathExpressions", List.of(".spec.conversion.webhook.clientConfig.caBundle")));

    /** OFF: a pipeline-step connector that is installed but held off until its phase is switched on. */
    public enum Status { HEALTHY, PROGRESSING, DEGRADED, NOT_INSTALLED, PLANNED, NOT_FOR_THIS_CLOUD, OFF, UNKNOWN }

    /** One connector's outcome in a phase switch. */
    public record PhaseStep(String id, String name, boolean ok, String detail) {}

    public record ProbeResult(String name, boolean ok, long millis, String detail) {}

    public record TestResult(Instant at, boolean ok, List<ProbeResult> probes) {}

    private final ConnectorCatalog catalog;
    private final ArgoCdService argo;
    private final KubernetesClient k8s;
    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, TestResult> lastTests = new ConcurrentHashMap<>();
    /** Held-off pipeline steps (Biome, Buildah); absent in unit tests that do not need them = all off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    PlatformSwitches switches;

    public ConnectorService(ConnectorCatalog catalog, ArgoCdService argo, KubernetesClient k8s, AppProperties props) {
        this.catalog = catalog;
        this.argo = argo;
        this.k8s = k8s;
        this.props = props;
    }

    public List<Map<String, Object>> list() {
        return catalog.all().stream().map(this::describe).toList();
    }

    Map<String, Object> describe(Connector c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("category", c.category());
        m.put("description", c.description());
        m.put("kind", c.kindEnum().name());
        m.put("clouds", c.clouds() == null ? List.of("any") : c.clouds());
        m.put("phase", c.phase());
        m.put("pinnedVersion", c.version());
        m.put("previousVersion", c.previousVersion());
        m.put("accessHint", c.accessHint());
        m.putAll(ConnectorCatalog.metadata(c));
        m.put("requires", c.requiresList().stream().map(id -> catalog.find(id).map(Connector::name).orElse(id)).toList());
        TestResult t = lastTests.get(c.id());
        m.put("lastTest", t);
        if (!c.supports(catalog.cloud())) {
            m.put("status", Status.NOT_FOR_THIS_CLOUD);
            m.put("message", "Supported on " + c.clouds() + "; this platform runs on " + catalog.cloud());
            return m;
        }
        try {
            switch (c.kindEnum()) {
                case PLANNED -> m.put("status", Status.PLANNED);
                case CORE -> {
                    if (switchable(c)) m.put("switchedOn", switchOn(c));
                    if (switchable(c) && !switchOn(c)) {
                        m.put("status", Status.OFF);
                        m.put("message", "Off until phase " + c.phase() + " is switched on");
                        return m;
                    }
                    var d = deploymentState(c);
                    m.put("status", d.status());
                    m.put("message", d.detail());
                }
                case MANAGED -> describeManaged(c, m, t);
            }
        } catch (RuntimeException e) {
            log.debug("connector {} status failed", c.id(), e);
            m.put("status", Status.UNKNOWN);
            m.put("message", TektonService.describe(e));
        }
        return m;
    }

    private void describeManaged(Connector c, Map<String, Object> m, TestResult t) {
        Map<String, Object> app = argo.getApp(c.appName(), false);
        if (app == null) {
            m.put("status", Status.NOT_INSTALLED);
            return;
        }
        String version = J.str(firstSource(app), "targetRevision");
        String health = J.str(app, "status", "health", "status");
        String sync = J.str(app, "status", "sync", "status");
        m.put("installedVersion", version);
        m.put("health", health);
        m.put("sync", sync);
        Status s = switch (health == null ? "" : health) {
            case "Healthy" -> Status.HEALTHY;
            case "Progressing" -> Status.PROGRESSING;
            case "" -> Status.UNKNOWN;
            default -> Status.DEGRADED;
        };
        // Argo can report Healthy while the tool does not answer; a failed test wins until the next pass
        if (s == Status.HEALTHY && t != null && !t.ok()) {
            s = Status.DEGRADED;
            m.put("message", "Last test failed");
        } else if (s != Status.HEALTHY) {
            m.put("message", J.str(app, "status", "health", "message"));
        }
        m.put("status", s);
    }

    private static Object firstSource(Map<String, Object> app) {
        List<Map<String, Object>> sources = J.list(app, "spec", "sources");
        return sources.isEmpty() ? J.get(app, "spec", "source") : sources.get(0);
    }

    private record DeploymentState(Status status, String detail) {}

    private DeploymentState deploymentState(Connector c) {
        try {
            Deployment d = k8s.apps().deployments().inNamespace(c.namespace()).withName(c.deployment()).get();
            if (d == null) return new DeploymentState(Status.NOT_INSTALLED, null);
            Integer ready = d.getStatus() == null ? null : d.getStatus().getAvailableReplicas();
            Integer want = d.getSpec() == null ? null : d.getSpec().getReplicas();
            String detail = (ready == null ? 0 : ready) + "/" + (want == null ? 1 : want) + " available";
            return new DeploymentState(ready != null && ready > 0 ? Status.HEALTHY : Status.DEGRADED, detail);
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            return new DeploymentState(Status.UNKNOWN, e.getCode() == 403
                    ? "No permission to read " + c.namespace() + "/" + c.deployment() + " (apply platform/deploy/rbac.yaml)"
                    : TektonService.describe(e));
        }
    }

    public TestResult test(String id) {
        Connector c = get(id);
        List<ProbeResult> results = new ArrayList<>();
        switch (c.kindEnum()) {
            case PLANNED -> throw new IllegalArgumentException(c.name() + " is planned and not installable yet");
            case CORE -> results.add(deploymentProbe(c));
            case MANAGED -> {
                long t0 = System.nanoTime();
                Map<String, Object> app = argo.getApp(c.appName(), false);
                if (app == null) throw new IllegalStateException(c.name() + " is not installed; enable it first");
                String health = J.str(app, "status", "health", "status");
                String sync = J.str(app, "status", "sync", "status");
                // Always reported, so tools without a Deployment or HTTP endpoint (CronJobs) still get a real check
                results.add(new ProbeResult("Argo CD app", "Healthy".equals(health), (System.nanoTime() - t0) / 1_000_000,
                        (health == null ? "no health yet" : health) + (sync == null ? "" : ", " + sync)));
                if (c.deployment() != null) results.add(deploymentProbe(c));
                for (Probe p : c.probeList()) results.add(probe(p));
            }
        }
        TestResult r = new TestResult(Instant.now(), results.stream().allMatch(ProbeResult::ok), List.copyOf(results));
        lastTests.put(id, r);
        return r;
    }

    private ProbeResult deploymentProbe(Connector c) {
        long t0 = System.nanoTime();
        var d = deploymentState(c);
        return new ProbeResult("Deployment " + c.deployment(), d.status() == Status.HEALTHY,
                (System.nanoTime() - t0) / 1_000_000, d.detail() == null ? d.status().name() : d.detail());
    }

    ProbeResult probe(Probe p) {
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(p.url()))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = res.statusCode() / 100 == 2 && (p.expect() == null || res.body().contains(p.expect()));
            return new ProbeResult(p.name(), ok, ms, "HTTP " + res.statusCode() + (ok ? "" : " (unexpected response)"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(p.name(), false, (System.nanoTime() - t0) / 1_000_000, "interrupted");
        } catch (Exception e) {
            return new ProbeResult(p.name(), false, (System.nanoTime() - t0) / 1_000_000,
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /** Installs (or re-pins) a managed connector at one of its allowed versions. */
    public String install(String id, String version) {
        Connector c = managed(id);
        String v = version == null || version.isBlank() ? c.version() : version;
        if (!c.versions().contains(v))
            throw new IllegalArgumentException("Version " + v + " is not allowed for " + c.name() + "; allowed: " + c.versions());
        List<String> missing = c.requiresList().stream().map(this::get).filter(r -> !present(r)).map(Connector::name).toList();
        if (!missing.isEmpty())
            throw new IllegalStateException(c.name() + " needs " + String.join(", ", missing) + " first");
        try {
            argo.upsertApplication(appSpec(c, v));
        } catch (HttpClientErrorException.Forbidden e) {
            throw new IllegalStateException("Argo CD refused: the aio-platform account needs access to the '" + PROJECT
                    + "' project (see docs/architecture/CONNECTORS.md)");
        } catch (RestClientException e) {
            throw new IllegalStateException("Argo CD error: " + e.getMessage());
        }
        lastTests.remove(id);
        return v;
    }

    /** Back to the previous pinned version (the degrade path when a new version misbehaves). */
    public String rollback(String id) {
        Connector c = managed(id);
        if (c.previousVersion() == null) throw new IllegalArgumentException(c.name() + " has no previous version");
        if (argo.getApp(c.appName(), false) == null) throw new IllegalStateException(c.name() + " is not installed");
        return install(id, c.previousVersion());
    }

    /** Removes the connector and everything it installed (cascade). */
    public void disable(String id) {
        Connector c = managed(id);
        List<String> users = catalog.all().stream()
                .filter(o -> o.requiresList().contains(id) && o.kindEnum() == ConnectorCatalog.Kind.MANAGED
                        && argo.getApp(o.appName(), false) != null)
                .map(Connector::name).toList();
        if (!users.isEmpty())
            throw new IllegalStateException(String.join(", ", users) + " still use " + c.name() + "; disable "
                    + (users.size() == 1 ? "it" : "them") + " first");
        argo.deleteApp(c.appName());
        lastTests.remove(id);
        deleteOwnNamespace(c);
    }

    // ---- Phase switch: hold a whole phase off until every system is ready, then switch it on (or off) at once ----

    /** A pipeline-step core connector (Biome; Podman / Buildah) that is switched rather than installed. */
    static boolean switchable(Connector c) {
        return c.kindEnum() == ConnectorCatalog.Kind.CORE && PlatformSwitches.PIPELINE_STEPS.contains(c.id());
    }

    private boolean switchOn(Connector c) {
        return switches != null && switches.on(c.id());
    }

    /** Members of a phase this platform can switch: managed connectors for this cloud and pipeline steps. */
    List<Connector> phaseMembers(String phase) {
        List<Connector> members = catalog.all().stream()
                .filter(c -> phase.equals(c.phase()) && c.supports(catalog.cloud())
                        && (c.kindEnum() == ConnectorCatalog.Kind.MANAGED || switchable(c)))
                .toList();
        return dependencyOrder(members);
    }

    /** Dependencies first (requires inside the phase), otherwise catalog order. */
    static List<Connector> dependencyOrder(List<Connector> members) {
        List<Connector> out = new ArrayList<>();
        List<Connector> left = new ArrayList<>(members);
        while (!left.isEmpty()) {
            Connector next = left.stream()
                    .filter(c -> c.requiresList().stream().noneMatch(r -> left.stream().anyMatch(o -> o.id().equals(r))))
                    .findFirst().orElse(left.getFirst());   // a cycle cannot block the switch; install reports it
            out.add(next);
            left.remove(next);
        }
        return out;
    }

    /** Phases with something to switch: how many members are on (installed or switched on). */
    public List<Map<String, Object>> phases() {
        java.util.TreeSet<String> ids = new java.util.TreeSet<>(java.util.Comparator
                .comparing((String p) -> p.matches("\\d+") ? Integer.parseInt(p) : Integer.MAX_VALUE).thenComparing(p -> p));
        catalog.all().forEach(c -> { if (c.phase() != null) ids.add(c.phase()); });
        List<Map<String, Object>> out = new ArrayList<>();
        for (String phase : ids) {
            List<Connector> members = phaseMembers(phase);
            if (members.isEmpty()) continue;
            long on = members.stream().filter(this::isOn).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", phase);
            m.put("connectors", members.stream().map(Connector::name).toList());
            m.put("on", on);
            m.put("total", members.size());
            out.add(m);
        }
        return out;
    }

    private boolean isOn(Connector c) {
        try {
            return switchable(c) ? switchOn(c) : argo.getApp(c.appName(), false) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Switches a whole phase on (enable every member at its pinned version, dependencies first) or off (disable in
     * reverse order, so nothing is removed while another member still needs it). Members that are already in the
     * wanted state are left alone. One failure does not stop the rest; each member reports its own outcome.
     */
    public List<PhaseStep> switchPhase(String phase, boolean on, String actor) {
        List<Connector> members = new ArrayList<>(phaseMembers(phase));
        if (members.isEmpty()) throw new IllegalArgumentException("Phase " + phase + " has nothing to switch on this cloud");
        if (!on) java.util.Collections.reverse(members);
        List<PhaseStep> steps = new ArrayList<>();
        for (Connector c : members) {
            try {
                if (switchable(c)) {
                    if (switches == null) throw new IllegalStateException("switches are unavailable");
                    boolean was = switches.on(c.id());
                    switches.set(c.id(), on, actor);
                    steps.add(new PhaseStep(c.id(), c.name(), true, was == on ? (on ? "already on" : "already off") : (on ? "switched on" : "switched off")));
                } else if (on) {
                    if (argo.getApp(c.appName(), false) != null) steps.add(new PhaseStep(c.id(), c.name(), true, "already on"));
                    else steps.add(new PhaseStep(c.id(), c.name(), true, "installing " + install(c.id(), null)));
                } else {
                    if (argo.getApp(c.appName(), false) == null) steps.add(new PhaseStep(c.id(), c.name(), true, "already off"));
                    else {
                        disable(c.id());
                        steps.add(new PhaseStep(c.id(), c.name(), true, "removing"));
                    }
                }
            } catch (RuntimeException e) {
                steps.add(new PhaseStep(c.id(), c.name(), false, e.getMessage()));
            }
        }
        return steps;
    }

    /**
     * Operators create objects Argo CD does not track (Trivy scan jobs, self-signed certificate Secrets), so a cascade
     * delete leaves them behind (seen live on AKS). The connector's namespace is removed too, but only when Argo CD
     * created it for this connector (label set through managedNamespaceMetadata): a shared namespace is never touched.
     */
    void deleteOwnNamespace(Connector c) {
        try {
            var ns = k8s.namespaces().withName(c.namespace()).get();
            if (ns == null || ns.getMetadata().getLabels() == null
                    || !c.id().equals(ns.getMetadata().getLabels().get(NS_LABEL))) return;
            k8s.namespaces().withName(c.namespace()).delete();
        } catch (RuntimeException e) {
            log.warn("connector {}: namespace {} not removed: {}", c.id(), c.namespace(), e.getMessage());
        }
    }

    /**
     * A dependency counts as present when its Argo CD app exists (managed) or its Deployment exists (core).
     * Missing read permission on a core Deployment is not treated as absent: the install then fails or
     * degrades visibly in Argo CD instead of being blocked by an RBAC gap.
     */
    private boolean present(Connector r) {
        return switch (r.kindEnum()) {
            case MANAGED -> argo.getApp(r.appName(), false) != null;
            case CORE -> deploymentState(r).status() != Status.NOT_INSTALLED;
            case PLANNED -> false;
        };
    }

    Map<String, Object> appSpec(Connector c, String version) {
        var g = props.gitops();
        // Values and extras come from the catalog repository at its pinned revision (else the GitOps repo)
        String valuesRepo = catalog.valuesRepo() != null ? catalog.valuesRepo() : g.repoUrl();
        String valuesRevision = catalog.revision() != null ? catalog.revision() : g.branch();
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("repoURL", c.chartRepo());
        chart.put("chart", c.chart());
        chart.put("targetRevision", version);
        chart.put("helm", Map.of("releaseName", c.releaseName(),
                "valueFiles", c.valueFiles(catalog.cloud(), catalog.site()).stream().map(f -> "$values/" + f).toList()));
        Map<String, Object> values = Map.of("repoURL", valuesRepo, "targetRevision", valuesRevision, "ref", "values");

        Map<String, Object> syncPolicy = new LinkedHashMap<>();
        syncPolicy.put("automated", Map.of("prune", true, "selfHeal", true));
        // Server-side apply: large CRDs (kube-prometheus-stack) exceed the client-side annotation limit
        // RespectIgnoreDifferences: self-heal must not undo fields other controllers own (see ignoreDifferences)
        syncPolicy.put("syncOptions", List.of("CreateNamespace=true", "ServerSideApply=true", "RespectIgnoreDifferences=true"));
        syncPolicy.put("retry", Map.of("limit", 3, "backoff", Map.of("duration", "15s", "factor", 2, "maxDuration", "3m")));
        // Marks the namespace Argo CD creates, so Disable can remove what operators leave behind (scan jobs, certs)
        syncPolicy.put("managedNamespaceMetadata", Map.of("labels", Map.of(NS_LABEL, c.id())));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("project", PROJECT);
        List<Map<String, Object>> sources = new ArrayList<>(List.of(chart, values));
        if (c.extrasPath() != null)
            sources.add(Map.of("repoURL", valuesRepo, "targetRevision", valuesRevision, "path", c.extrasPath()));
        spec.put("sources", sources);
        spec.put("destination", Map.of("server", HUB, "namespace", c.namespace()));
        spec.put("syncPolicy", syncPolicy);
        spec.put("ignoreDifferences", IGNORE_DIFFERENCES);
        return Map.of(
                "metadata", Map.of("name", c.appName(), "namespace", "argocd",
                        "labels", Map.of("aio.platform/connector", c.id()),
                        // Server-side diff: defaults and injected fields (CRD CA bundles) are not reported as drift
                        "annotations", Map.of("argocd.argoproj.io/compare-options", "ServerSideDiff=true")),
                "spec", spec);
    }

    private Connector get(String id) {
        return catalog.find(id).orElseThrow(() -> new NoSuchElementException("Unknown connector " + id));
    }

    private Connector managed(String id) {
        Connector c = get(id);
        if (!c.supports(catalog.cloud()))
            throw new IllegalArgumentException(c.name() + " is not supported on " + catalog.cloud());
        if (c.kindEnum() != ConnectorCatalog.Kind.MANAGED)
            throw new IllegalArgumentException(c.name() + (c.kindEnum() == ConnectorCatalog.Kind.CORE
                    ? " is a core component; manage it with the bootstrap scripts"
                    : " is planned and not installable yet"));
        return c;
    }
}
