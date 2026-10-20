package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Argo CD REST API: one Application per project per environment. */
@Service
public class ArgoCdService {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private final RestClient argo;
    private final AppProperties props;

    public ArgoCdService(RestClient argoRestClient, AppProperties props) {
        this.argo = argoRestClient;
        this.props = props;
    }

    public static String appName(Project p, String env) {
        return p.name + "-" + env;
    }

    /** Upserts the Argo CD Application. dev/staging auto-sync; prod is manual (approval gate). */
    public void upsertApp(Project p, String env) {
        var g = props.gitops();
        var a = props.argocd();
        String chartToRoot = "../".repeat(g.chartPath().split("/").length);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("project", a.project());
        spec.put("source", Map.of(
                "repoURL", g.repoUrl(),
                "targetRevision", g.branch(),
                "path", g.chartPath(),
                "helm", Map.of(
                        "releaseName", p.name,
                        "valueFiles", List.of("values.yaml", chartToRoot + GitOpsService.valuesPath(g, p.name, env)))));
        spec.put("destination", Map.of("server", a.destinationServer(), "namespace", appName(p, env)));

        Map<String, Object> syncPolicy = new LinkedHashMap<>();
        syncPolicy.put("syncOptions", List.of("CreateNamespace=true"));
        syncPolicy.put("retry", Map.of("limit", 3,
                "backoff", Map.of("duration", "10s", "factor", 2, "maxDuration", "3m")));
        if (!"prod".equals(env)) syncPolicy.put("automated", Map.of("prune", true, "selfHeal", true));
        spec.put("syncPolicy", syncPolicy);

        Map<String, Object> app = Map.of(
                "metadata", Map.of(
                        "name", appName(p, env),
                        "namespace", "argocd",
                        "labels", Map.of("aio.platform/project", p.name, "env", env)),
                "spec", spec);

        argo.post().uri("/api/v1/applications?upsert=true").body(app).retrieve().toBodilessEntity();
    }

    /** Returns the Application JSON or null if it does not exist. */
    public Map<String, Object> getApp(String name, boolean refresh) {
        try {
            return argo.get()
                    .uri(refresh ? "/api/v1/applications/{n}?refresh=normal" : "/api/v1/applications/{n}", name)
                    .retrieve().body(MAP);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            return null;
        }
    }

    public void sync(String name) {
        argo.post().uri("/api/v1/applications/{n}/sync", name)
                .body(Map.of("prune", true))
                .retrieve().toBodilessEntity();
    }
}
