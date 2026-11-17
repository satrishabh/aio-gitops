package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.CiRun;
import com.aio.platform.repo.CiRunRepo;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RegistryRunCleanup {
    private final CiRunRepo runs;
    private final KubernetesClient k8s;
    private final AppProperties props;
    public RegistryRunCleanup(CiRunRepo runs, KubernetesClient k8s, AppProperties props) {
        this.runs = runs; this.k8s = k8s; this.props = props;
    }
    @Scheduled(fixedDelayString = "${app.delivery.secret-cleanup-ms:30000}")
    public void cleanup() {
        for (CiRun run : runs.findByExternalRegistryTrueAndRegistrySecretsCleanedFalseAndStatusIn(
                Set.of(CiRun.SUCCEEDED, CiRun.FAILED, CiRun.CANCELLED), PageRequest.of(0, 50))) {
            try {
                if (run.pipelineRunName == null && (run.finishedAt == null || run.finishedAt.isAfter(java.time.Instant.now().minusSeconds(300)))) continue;
                var context = new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                        .withGroup("tekton.dev").withVersion("v1").withPlural("pipelineruns").withKind("PipelineRun").withNamespaced(true).build();
                var observed = k8s.genericKubernetesResources(context).inNamespace(props.tekton().namespace())
                        .withLabel("aio.platform/run-id", run.id.toString()).list();
                boolean active = observed.getItems().stream().anyMatch(resource -> J.list(resource.getAdditionalProperties().get("status"), "conditions")
                        .stream().noneMatch(condition -> "Succeeded".equals(condition.get("type"))
                                && java.util.Set.of("True", "False").contains(String.valueOf(condition.get("status")))));
                if (active) continue;
                for (String access : Set.of("push", "pull")) {
                    var resource = k8s.secrets().inNamespace(props.tekton().namespace()).withName(RegistryConnections.runSecret(run.id, access));
                    var secret = resource.get();
                    if (secret == null) continue;
                    var labels = secret.getMetadata().getLabels();
                    if (labels == null || !run.id.toString().equals(labels.get("aio.platform/run-id"))
                            || !run.projectId.toString().equals(labels.get("aio.platform/project-id")))
                        throw new SecurityException("Run secret ownership mismatch");
                    resource.delete();
                }
                run.registrySecretsCleaned = true;
                runs.save(run);
            } catch (RuntimeException ignored) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Registry secret cleanup pending for run {}", run.id);
            }
        }
    }
}
