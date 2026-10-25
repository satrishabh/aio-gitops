package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.RunStep;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.RunStepRepo;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Cluster-side housekeeping the platform owns:
 * <ul>
 *   <li>app namespaces {@code <project>-<env>} with a {@code ghcr-pull} secret, so private GHCR images deploy;</li>
 *   <li>startup reconcile of namespaces, secrets, GitOps values and Argo CD apps for every project;</li>
 *   <li>pruning old PipelineRuns (step logs are archived to the DB first, so history stays viewable).</li>
 * </ul>
 */
@Service
public class ClusterService {
    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);
    private static final int MAX_LOG_CHARS = 256 * 1024;
    private static final ResourceDefinitionContext PIPELINE_RUN = new ResourceDefinitionContext.Builder()
            .withGroup("tekton.dev").withVersion("v1").withPlural("pipelineruns").withKind("PipelineRun")
            .withNamespaced(true).build();

    private final KubernetesClient k8s;
    private final AppProperties props;
    private final ProjectRepo projects;
    private final CiRunRepo runs;
    private final RunStepRepo steps;
    private final TektonService tekton;
    private final GitOpsService gitops;
    private final ArgoCdService argo;
    private final AuditService audit;

    public ClusterService(KubernetesClient k8s, AppProperties props, ProjectRepo projects, CiRunRepo runs,
                          RunStepRepo steps, TektonService tekton, GitOpsService gitops, ArgoCdService argo,
                          AuditService audit) {
        this.k8s = k8s;
        this.props = props;
        this.projects = projects;
        this.runs = runs;
        this.steps = steps;
        this.tekton = tekton;
        this.gitops = gitops;
        this.argo = argo;
        this.audit = audit;
    }

    /**
     * Shared environment namespaces: every project deploys into {@code dev}, {@code staging} or {@code prod}
     * as its own Helm release (Deployment/Service/Ingress named after the project).
     */
    public static String namespace(Project p, String env) {
        return env;
    }

    /** Namespace used by the earlier one-namespace-per-project-per-env layout (migrated away on reconcile). */
    static String legacyNamespace(Project p, String env) {
        return p.name + "-" + env;
    }

    // ------------------------------------------------------------------ namespaces + pull secrets

    /**
     * Ensures the shared env namespace exists with Pod Security labels and a default LimitRange
     * (containers without resources still get sane requests/limits), and upserts its ghcr-pull secret.
     */
    public void ensureEnvironment(Project p, String env) {
        String ns = namespace(p, env);
        k8s.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(ns)
                .addToLabels("aio.platform/managed", "true")
                .addToLabels("aio.platform/env", env)
                .addToLabels("pod-security.kubernetes.io/enforce", "baseline")
                .addToLabels("pod-security.kubernetes.io/warn", "restricted")
                .endMetadata().build()).forceConflicts().serverSideApply();
        k8s.limitRanges().inNamespace(ns).resource(new LimitRangeBuilder()
                .withNewMetadata().withName("defaults").withNamespace(ns).endMetadata()
                .withNewSpec().addNewLimit().withType("Container")
                .addToDefaultRequest("cpu", new Quantity("50m")).addToDefaultRequest("memory", new Quantity("64Mi"))
                .addToDefault("cpu", new Quantity("500m")).addToDefault("memory", new Quantity("512Mi"))
                .endLimit().endSpec().build()).forceConflicts().serverSideApply();
        String token = props.github().token();
        if (token == null || token.isBlank()) {
            log.warn("GITHUB_TOKEN not set; skipping {} in {}", GitOpsService.PULL_SECRET, ns);
            return;
        }
        k8s.secrets().inNamespace(ns).resource(new SecretBuilder()
                .withNewMetadata().withName(GitOpsService.PULL_SECRET).withNamespace(ns)
                .addToLabels("aio.platform/managed", "true").endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .addToData(".dockerconfigjson", b64(dockerConfig(registryHost(), props.registryUser(), token)))
                .build()).forceConflicts().serverSideApply();
    }

    /** Deletes the old per-project namespace, but only if the platform created it for this project. */
    public void deleteLegacyNamespace(Project p, String env) {
        var ns = k8s.namespaces().withName(legacyNamespace(p, env)).get();
        if (ns == null || ns.getMetadata().getLabels() == null) return;
        if (!p.name.equals(ns.getMetadata().getLabels().get("aio.platform/project"))) return;
        k8s.namespaces().withName(legacyNamespace(p, env)).delete();
        log.info("deleted legacy namespace {}", legacyNamespace(p, env));
    }

    static String dockerConfig(String host, String user, String token) {
        String auth = Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
        return "{\"auths\":{\"" + host + "\":{\"auth\":\"" + auth + "\"}}}";
    }

    private String registryHost() {
        String r = props.registry();
        int slash = r.indexOf('/');
        return slash > 0 ? r.substring(0, slash) : r;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ startup reconcile

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAll() {
        for (Project p : projects.findAll()) {
            try {
                reconcile(p);
            } catch (Exception e) {
                log.warn("reconcile of {} failed: {}", p.name, e.getMessage());
            }
        }
    }

    public void reconcile(Project p) {
        for (String env : GitOpsService.ENVS) ensureEnvironment(p, env);
        gitops.reconcile(p);
        for (String env : GitOpsService.ENVS) {
            // Remove the legacy per-project namespace first: its Ingress claims the same host, and the
            // ingress-nginx admission webhook would reject the release in the shared namespace.
            deleteLegacyNamespace(p, env);
            try {
                argo.upsertApp(p, env, true); // moves the app into the shared namespace (syncs prod once)
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (!e.getResponseBodyAsString().contains("already in progress")) throw e;
                log.info("{}-{}: sync already in progress, leaving it to Argo CD", p.name, env);
            }
        }
        log.info("reconciled {} (namespaces dev/staging/prod, {}, values, Argo CD apps)", p.name, GitOpsService.PULL_SECRET);
    }

    // ------------------------------------------------------------------ PipelineRun pruning

    @Scheduled(fixedDelayString = "${app.watch.cleanup-ms:600000}", initialDelayString = "${app.watch.cleanup-initial-ms:120000}")
    public void prune() {
        int keep = Math.max(1, props.cleanup().keepPerProject());
        Instant cutoff = Instant.now().minus(Duration.ofHours(Math.max(1, props.cleanup().maxAgeHours())));
        List<GenericKubernetesResource> all = k8s.genericKubernetesResources(PIPELINE_RUN)
                .inNamespace(props.tekton().namespace()).withLabel("aio.platform/project").list().getItems();

        Map<String, List<GenericKubernetesResource>> byProject = all.stream()
                .collect(Collectors.groupingBy(r -> r.getMetadata().getLabels().get("aio.platform/project")));
        int deleted = 0;
        for (var entry : byProject.entrySet()) {
            List<GenericKubernetesResource> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing((GenericKubernetesResource r) -> r.getMetadata().getCreationTimestamp()).reversed())
                    .toList();
            for (int i = keep; i < sorted.size(); i++) {
                GenericKubernetesResource pr = sorted.get(i);
                if (!isFinished(pr) || Instant.parse(pr.getMetadata().getCreationTimestamp()).isAfter(cutoff)) continue;
                try {
                    archiveLogs(pr.getMetadata().getName());
                    k8s.genericKubernetesResources(PIPELINE_RUN).inNamespace(props.tekton().namespace())
                            .withName(pr.getMetadata().getName()).delete();
                    deleted++;
                } catch (Exception e) {
                    log.warn("prune of {} failed: {}", pr.getMetadata().getName(), e.getMessage());
                }
            }
        }
        if (deleted > 0) {
            log.info("pruned {} PipelineRuns", deleted);
            audit.record("system", "cleanup.pipelineruns", null, "deleted " + deleted);
        }
    }

    private static boolean isFinished(GenericKubernetesResource pr) {
        return J.list(pr.getAdditionalProperties().get("status"), "conditions").stream()
                .anyMatch(c -> "Succeeded".equals(c.get("type")) && !"Unknown".equals(c.get("status")));
    }

    /** Copies step logs from the (soon to be deleted) pods into run_steps.log_text. */
    void archiveLogs(String pipelineRunName) {
        CiRun run = runs.findByPipelineRunName(pipelineRunName).orElse(null);
        if (run == null || run.logsArchived) return;
        for (RunStep s : steps.findByRunIdOrderById(run.id)) {
            if (s.logText != null || s.podName == null) continue;
            String text = tekton.logs(s.podName, s.containerName);
            s.logText = text.length() > MAX_LOG_CHARS ? text.substring(text.length() - MAX_LOG_CHARS) : text;
            steps.save(s);
        }
        run.logsArchived = true;
        runs.save(run);
    }
}
