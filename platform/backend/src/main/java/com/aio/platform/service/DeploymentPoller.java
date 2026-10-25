package com.aio.platform.service;

import com.aio.platform.domain.Deployment;
import com.aio.platform.domain.EnvStatus;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.DeploymentRepo;
import com.aio.platform.repo.EnvStatusRepo;
import com.aio.platform.repo.ProjectRepo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls Argo CD for every project environment: live sync/health, running image and deployment history. */
@Component
public class DeploymentPoller {
    private static final Logger log = LoggerFactory.getLogger(DeploymentPoller.class);

    private final ProjectRepo projects;
    private final EnvStatusRepo envs;
    private final DeploymentRepo deployments;
    private final ArgoCdService argo;
    private final GitOpsService gitops;
    private final AuditService audit;

    public DeploymentPoller(ProjectRepo projects, EnvStatusRepo envs, DeploymentRepo deployments,
                            ArgoCdService argo, GitOpsService gitops, AuditService audit) {
        this.projects = projects;
        this.envs = envs;
        this.deployments = deployments;
        this.argo = argo;
        this.gitops = gitops;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.watch.argocd-ms:15000}")
    public void poll() {
        for (Project p : projects.findAll()) {
            for (String env : GitOpsService.ENVS) {
                try {
                    syncEnv(p, env);
                } catch (Exception e) {
                    log.debug("argo poll {}-{} failed: {}", p.name, env, e.getMessage());
                }
            }
        }
    }

    void syncEnv(Project p, String env) {
        Map<String, Object> app = argo.getApp(ArgoCdService.appName(p, env), false);
        if (app == null) return;
        Object status = app.get("status");

        String tag = currentTag(p, status);
        EnvStatus es = envs.findByProjectIdAndEnvironment(p.id, env).orElseGet(() -> {
            EnvStatus n = new EnvStatus();
            n.projectId = p.id;
            n.environment = env;
            return n;
        });
        es.syncStatus = J.str(status, "sync", "status");
        es.healthStatus = J.str(status, "health", "status");
        es.revision = J.str(status, "sync", "revision");
        es.imageTag = tag;
        try {
            var desired = gitops.readValuesCached(p, env);
            es.desiredTag = desired.tag();
            es.replicas = desired.replicas();
            es.appUrl = desired.appUrl();
        } catch (Exception e) {
            log.debug("gitops read {}-{} failed: {}", p.name, env, e.getMessage());
        }
        es.updatedAt = Instant.now();
        envs.save(es);

        List<Map<String, Object>> history = J.list(status, "history");
        long latestId = history.stream().mapToLong(h -> ((Number) h.get("id")).longValue()).max().orElse(-1);
        for (Map<String, Object> h : history) {
            long id = ((Number) h.get("id")).longValue();
            if (deployments.existsByProjectIdAndEnvironmentAndArgoHistoryId(p.id, env, id)) continue;
            Deployment d = new Deployment();
            d.projectId = p.id;
            d.environment = env;
            d.argoHistoryId = id;
            d.revision = J.str(h, "revision");
            d.deployedAt = J.time(h, "deployedAt");
            // Argo history doesn't store the image; we know it only for the latest entry. Argo's image summary
            // lags behind a rollout, so prefer the GitOps tag once the app is Synced to that revision.
            String deployedTag = "Synced".equals(es.syncStatus) && es.desiredTag != null ? es.desiredTag : tag;
            d.imageTag = id == latestId ? deployedTag : null;
            deployments.save(d);
            if (id == latestId) audit.record("argocd", "deploy." + env, p.name, deployedTag);
        }
    }

    private static String currentTag(Project p, Object status) {
        Object images = J.get(status, "summary", "images");
        if (!(images instanceof List<?> list)) return null;
        String prefix = p.imageRepo + ":";
        for (Object o : list) {
            String img = String.valueOf(o);
            if (img.startsWith(prefix)) return img.substring(prefix.length());
        }
        return null;
    }
}
