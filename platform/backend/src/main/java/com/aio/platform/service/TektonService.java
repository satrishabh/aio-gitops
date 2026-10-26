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
        params.put("image", p.imageRepo);
        params.put("dockerfile", p.dockerfile);
        params.put("context", p.contextDir);
        params.put("test-image", p.testImage);
        params.put("test-command", p.testCommand == null ? "" : p.testCommand);
        params.put("gitops-repo", g.repoUrl());
        params.put("gitops-branch", g.branch());
        params.put("gitops-values-file", GitOpsService.valuesPath(g, p.name, "dev"));
        params.put("deploy", String.valueOf(run.deploy));
        params.put("scan-severity", run.scanSeverity == null ? "HIGH,CRITICAL" : run.scanSeverity);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("pipelineRef", Map.of("name", t.pipeline()));
        spec.put("params", params.entrySet().stream()
                .map(e -> Map.<String, Object>of("name", e.getKey(), "value", e.getValue())).toList());
        // hard cap on the whole run (scheduling, build, scan, GitOps update); Tekton stops every task when it expires
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

        return createWithRetry(pr, run.id);
    }

    static final long[] BACKOFF_MS = {1_000, 3_000};

    /**
     * Transient failures (API server/webhook briefly unavailable, dropped connection, stale token) are retried.
     * The last attempt uses a freshly built client, so a long-lived client in a bad state cannot block CI;
     * before each retry we look the run up by label so a request that did reach the API server is not duplicated.
     */
    private String createWithRetry(GenericKubernetesResource pr, Long runId) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            boolean fresh = attempt == BACKOFF_MS.length && attempt > 0;
            try {
                if (attempt > 0) {
                    String existing = findByRunId(runId);
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

    private String findByRunId(Long runId) {
        try {
            var items = k8s.genericKubernetesResources(PIPELINE_RUN).inNamespace(ns())
                    .withLabel("aio.platform/run-id", String.valueOf(runId)).list().getItems();
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
