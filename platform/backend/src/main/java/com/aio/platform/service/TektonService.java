package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Creates and inspects Tekton PipelineRuns/TaskRuns and reads step logs from their pods. */
@Service
public class TektonService {
    private static final Logger log = LoggerFactory.getLogger(TektonService.class);
    private static final ResourceDefinitionContext PIPELINE_RUN = ctx("pipelineruns", "PipelineRun");
    private static final ResourceDefinitionContext TASK_RUN = ctx("taskruns", "TaskRun");

    private final KubernetesClient k8s;
    private final AppProperties props;
    @org.springframework.beans.factory.annotation.Value("${app.delivery.pipeline:ci-build-v2}")
    private String buildPipeline = "ci-build-v2";
    @org.springframework.beans.factory.annotation.Value("${app.delivery.source-secret:github-source-token}")
    private String sourceSecret = "github-source-token";
    @org.springframework.beans.factory.annotation.Value("${app.delivery.registry-read-secret:registry-read-credentials}")
    private String registryReadSecret = "registry-read-credentials";
    @org.springframework.beans.factory.annotation.Value("${app.delivery.test-images:alpine:3.20,node:20-alpine,python:3.12-slim,maven:3.9-eclipse-temurin-21,golang:1.23-alpine}")
    private String approvedTestImages = "alpine:3.20,node:20-alpine,python:3.12-slim,maven:3.9-eclipse-temurin-21,golang:1.23-alpine";

    public TektonService(KubernetesClient k8s, AppProperties props) {
        this.k8s = k8s;
        this.props = props;
    }

    private static ResourceDefinitionContext ctx(String plural, String kind) {
        return new ResourceDefinitionContext.Builder()
                .withGroup("tekton.dev").withVersion("v1").withPlural(plural).withKind(kind).withNamespaced(true)
                .build();
    }

    private String ns() {
        return props.tekton().namespace();
    }

    /**
     * fabric8 often reports only "An error has occurred."; the useful part (HTTP code, API server or
     * admission-webhook message, connection failure) is in the status or the cause chain.
     */
    public static String describe(Throwable e) {
        StringBuilder b = new StringBuilder();
        if (e instanceof io.fabric8.kubernetes.client.KubernetesClientException k) {
            if (k.getCode() > 0) b.append("HTTP ").append(k.getCode()).append(": ");
            var st = k.getStatus();
            if (st != null && st.getMessage() != null && !st.getMessage().isBlank()) b.append(st.getMessage());
            else if (st != null && st.getReason() != null) b.append(st.getReason());
        }
        // walk the cause chain for the root reason (e.g. UnknownHost, connect timeout, 403 from RBAC)
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String top = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (b.isEmpty()) b.append(top);
        if (root != e) b.append(" (cause: ").append(root.getClass().getSimpleName())
                .append(root.getMessage() == null ? "" : ": " + root.getMessage()).append(')');
        String s = b.toString();
        return s.length() > 900 ? s.substring(0, 900) + "…" : s;
    }

    /** Max duration of a whole run (Tekton duration string, e.g. "15m"). */
    public String timeout() {
        String v = props.tekton().timeout();
        return v == null || v.isBlank() ? "15m" : v;
    }

    /** Creates a PipelineRun of the shared ci-pipeline for this project; returns its generated name. */
    public String createPipelineRun(Project p, CiRun run) {
        var t = props.tekton();
        var g = props.gitops();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("repo-url", p.repoUrl);
        params.put("revision", run.gitRef);
        params.put("app-name", p.name);
        params.put("image", run.releaseImageRepo == null ? p.imageRepo : run.releaseImageRepo);
        params.put("dockerfile", p.dockerfile);
        params.put("context", p.contextDir);
        params.put("test-image", p.testImage);
        params.put("test-command", p.testCommand == null ? "" : p.testCommand);
        params.put("gitops-repo", g.repoUrl());
        params.put("gitops-branch", g.branch());
        params.put("gitops-values-file", GitOpsService.valuesPath(g, p.name, "dev"));
        params.put("deploy", String.valueOf(run.deploy));
        params.put("scan-severity", run.scanSeverity == null ? "HIGH,CRITICAL" : run.scanSeverity);

        boolean independent = "INDEPENDENT".equals(run.deliveryMode);
        if (independent) {
            if (!java.util.Arrays.asList(approvedTestImages.split(",")).contains(p.testImage))
                throw new IllegalArgumentException("Test image is not approved for the standard-v1 build profile");
            if (sourceSecret.equals(t.githubTokenSecret()))
                throw new IllegalArgumentException("Independent builds require a separate read-only source credential");
            if (!run.externalRegistry && registryReadSecret.equals(t.registrySecret()))
                throw new IllegalArgumentException("Independent builds require separate registry push and scan credentials");
            params.keySet().removeAll(java.util.Set.of("gitops-repo", "gitops-branch", "gitops-values-file", "deploy", "app-name"));
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("pipelineRef", Map.of("name", independent ? buildPipeline : t.pipeline()));
        spec.put("params", params.entrySet().stream()
                .map(e -> Map.<String, Object>of("name", e.getKey(), "value", e.getValue())).toList());
        // hard cap on the whole run (scheduling, build, scan, GitOps update); Tekton stops every task when it expires
        spec.put("timeouts", Map.of("pipeline", timeout()));
        spec.put("taskRunTemplate", Map.of(
                "serviceAccountName", t.serviceAccount(),
                "podTemplate", Map.of("securityContext", Map.of("fsGroup", 65532))));
        boolean externalRegistry = independent && run.externalRegistry;
        java.util.List<Map<String, Object>> workspaces = new java.util.ArrayList<>(List.of(
                Map.of("name", "source", "volumeClaimTemplate", Map.of("spec", Map.of(
                        "accessModes", List.of("ReadWriteOnce"),
                        "resources", Map.of("requests", Map.of("storage", t.storageSize()))))),
                Map.of("name", "dockerconfig", "secret", Map.of("secretName", externalRegistry ? RegistryConnections.runSecret(run.id, "push") : t.registrySecret())),
                Map.of("name", "github-token", "secret", Map.of("secretName", independent ? sourceSecret : t.githubTokenSecret()))));
        if (independent) workspaces.add(Map.of("name", "scanconfig", "secret", Map.of("secretName",
                externalRegistry ? RegistryConnections.runSecret(run.id, "pull") : registryReadSecret)));
        spec.put("workspaces", workspaces);

        GenericKubernetesResource pr = new GenericKubernetesResource();
        pr.setApiVersion("tekton.dev/v1");
        pr.setKind("PipelineRun");
        pr.setMetadata(new ObjectMetaBuilder()
                .withGenerateName(p.name + "-ci-")
                .withNamespace(ns())
                .addToLabels("aio.platform/project", p.name)
                .addToLabels("aio.platform/run-id", String.valueOf(run.id))
                .build());
        pr.setAdditionalProperty("spec", spec);

        if (independent) {
            pr.getMetadata().setGenerateName(null);
            pr.getMetadata().setName("aio-run-" + run.id);
            pr.getMetadata().getLabels().put("aio.platform/project-id", p.id.toString());
        }

        return createWithRetry(pr, "aio.platform/run-id", run.id);
    }

    public String recoverIndependentRun(CiRun run) {
        var resource = getPipelineRun("aio-run-" + run.id);
        if (resource == null) return null;
        var labels = resource.getMetadata().getLabels();
        if (labels == null || !run.id.toString().equals(labels.get("aio.platform/run-id"))
                || !run.projectId.toString().equals(labels.get("aio.platform/project-id")))
            throw new SecurityException("PipelineRun identity does not match the persisted run");
        return resource.getMetadata().getName();
    }

    /** Pipeline name of the standalone security scan (tekton/pipelines/security-scan.yaml). */
    public static final String SCAN_PIPELINE = "security-scan";

    /** Creates a PipelineRun of the security-scan pipeline for a scan; returns its generated name. */
    public String createScanRun(com.aio.platform.domain.ScanRun scan, java.util.Set<String> scanners, String projectName) {
        var t = props.tekton();
        Map<String, String> params = scanParams(scan, scanners);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("pipelineRef", Map.of("name", SCAN_PIPELINE));
        spec.put("params", params.entrySet().stream()
                .map(e -> Map.<String, Object>of("name", e.getKey(), "value", e.getValue())).toList());
        spec.put("timeouts", Map.of("pipeline", timeout()));
        spec.put("taskRunTemplate", Map.of(
                "serviceAccountName", t.serviceAccount(),
                "podTemplate", Map.of("securityContext", Map.of("fsGroup", 65532))));
        spec.put("workspaces", List.of(
                Map.of("name", "source", "volumeClaimTemplate", Map.of("spec", Map.of(
                        "accessModes", List.of("ReadWriteOnce"),
                        "resources", Map.of("requests", Map.of("storage", t.storageSize()))))),
                Map.of("name", "dockerconfig", "secret", Map.of("secretName", t.registrySecret())),
                Map.of("name", "github-token", "secret", Map.of("secretName", t.githubTokenSecret()))));

        var meta = new ObjectMetaBuilder()
                .withGenerateName("scan-" + (projectName == null ? "repo" : projectName) + "-")
                .withNamespace(ns())
                .addToLabels("aio.platform/scan-id", String.valueOf(scan.id));
        if (projectName != null) meta.addToLabels("aio.platform/scan-project", projectName);
        GenericKubernetesResource pr = new GenericKubernetesResource();
        pr.setApiVersion("tekton.dev/v1");
        pr.setKind("PipelineRun");
        pr.setMetadata(meta.build());
        pr.setAdditionalProperty("spec", spec);
        return createWithRetry(pr, "aio.platform/scan-id", scan.id);
    }

    /** security-scan params: one on/off switch per scanner; image scanning only when an image is given. */
    static Map<String, String> scanParams(com.aio.platform.domain.ScanRun scan, java.util.Set<String> scanners) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("repo-url", scan.repoUrl);
        params.put("revision", scan.gitRef);
        params.put("image", scan.image == null ? "" : scan.image);
        for (String s : List.of("vuln", "secret", "misconfig", "license"))
            params.put("scan-" + s, String.valueOf(scanners.contains(s)));
        params.put("scan-image", String.valueOf(scanners.contains("image") && scan.image != null && !scan.image.isBlank()));
        params.put("fail-on", scan.failOn == null || scan.failOn.isBlank() ? "NONE" : scan.failOn);
        params.put("ignore-unfixed", String.valueOf(scan.ignoreUnfixed));
        return params;
    }

    static final long[] BACKOFF_MS = {1_000, 3_000};

    /**
     * Transient failures (API server/webhook briefly unavailable, dropped connection, stale token) are retried.
     * The last attempt uses a freshly built client, so a long-lived client in a bad state cannot block CI;
     * before each retry we look the run up by label so a request that did reach the API server is not duplicated.
     */
    private String createWithRetry(GenericKubernetesResource pr, String idLabel, Long runId) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            boolean fresh = attempt == BACKOFF_MS.length && attempt > 0;
            try {
                if (attempt > 0) {
                    String existing = findByLabel(idLabel, runId);
                    if (existing != null) return existing;
                }
                if (!fresh) return create(k8s, pr);
                try (KubernetesClient c = new io.fabric8.kubernetes.client.KubernetesClientBuilder().build()) {
                    return create(c, pr);
                }
            } catch (RuntimeException e) {
                last = e;
                if (!isRetryable(e) || attempt == BACKOFF_MS.length) break;
                log.warn("PipelineRun create for run {} failed (attempt {}): {} - retrying", runId, attempt + 1, describe(e));
                sleep(BACKOFF_MS[attempt]);
            }
        }
        throw last;
    }

    private String create(KubernetesClient c, GenericKubernetesResource pr) {
        return c.genericKubernetesResources(PIPELINE_RUN).inNamespace(ns()).resource(pr).create().getMetadata().getName();
    }

    private String findByLabel(String idLabel, Long runId) {
        try {
            var items = k8s.genericKubernetesResources(PIPELINE_RUN).inNamespace(ns())
                    .withLabel(idLabel, String.valueOf(runId)).list().getItems();
            return items.isEmpty() ? null : items.get(0).getMetadata().getName();
        } catch (RuntimeException e) {
            return null; // lookup is best effort; the create attempt below reports the real problem
        }
    }

    /** Client errors that retrying cannot fix (bad spec, forbidden, missing pipeline) fail fast. */
    static boolean isRetryable(Throwable e) {
        if (!(e instanceof io.fabric8.kubernetes.client.KubernetesClientException k)) return true;
        int code = k.getCode();
        return code <= 0 || code == 401 || code == 408 || code == 429 || code >= 500;   // <= 0: no HTTP response (I/O)
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    public GenericKubernetesResource getPipelineRun(String name) {
        return k8s.genericKubernetesResources(PIPELINE_RUN).inNamespace(ns()).withName(name).get();
    }

    public GenericKubernetesResource getTaskRun(String name) {
        return k8s.genericKubernetesResources(TASK_RUN).inNamespace(ns()).withName(name).get();
    }

    /** Security-scan PipelineRuns (label aio.platform/scan-id), for pruning. */
    public List<GenericKubernetesResource> listScanRuns() {
        return k8s.genericKubernetesResources(PIPELINE_RUN).inNamespace(ns()).withLabel("aio.platform/scan-id").list().getItems();
    }

    public void deletePipelineRun(String name) {
        k8s.genericKubernetesResources(PIPELINE_RUN).inNamespace(ns()).withName(name).delete();
    }

    @SuppressWarnings("unchecked")
    public void cancel(String pipelineRunName) {
        k8s.genericKubernetesResources(PIPELINE_RUN).inNamespace(ns()).withName(pipelineRunName).edit(r -> {
            ((Map<String, Object>) r.getAdditionalProperties().get("spec")).put("status", "Cancelled");
            return r;
        });
    }

    public String logs(String pod, String container) {
        if (pod == null || container == null) return "";
        try {
            String text = k8s.pods().inNamespace(ns()).withName(pod).inContainer(container).tailingLines(3000).getLog();
            return text == null ? "" : text;
        } catch (Exception e) {
            log.debug("logs unavailable for {}/{}: {}", pod, container, e.getMessage());
            return "(logs not available yet: " + e.getMessage() + ")";
        }
    }
}
