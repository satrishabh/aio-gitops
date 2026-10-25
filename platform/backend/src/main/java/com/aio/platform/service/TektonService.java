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

        return k8s.genericKubernetesResources(PIPELINE_RUN).inNamespace(ns()).resource(pr).create()
                .getMetadata().getName();
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
