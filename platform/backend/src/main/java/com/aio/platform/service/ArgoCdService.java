package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.config.AppProperties.Cluster;
import com.aio.platform.domain.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Argo CD REST API: one Application per project per environment, deployed to the environment's cluster
 * ({@link ClusterRegistry}). Argo CD is the only component holding workload-cluster credentials, so logs,
 * live manifests and Rollout actions also go through it.
 */
@Service
public class ArgoCdService {
    private static final Logger log = LoggerFactory.getLogger(ArgoCdService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DELETE_WAIT = Duration.ofMinutes(4);

    private final RestClient argo;
    private final AppProperties props;
    private final ClusterRegistry clusters;

    public ArgoCdService(RestClient argoRestClient, AppProperties props, ClusterRegistry clusters) {
        this.argo = argoRestClient;
        this.props = props;
        this.clusters = clusters;
    }

    public static String appName(Project p, String env) {
        return p.name + "-" + env;
    }

    /** What {@link #upsertApp} did. MOVE_PENDING: the app sits on another cluster and moving it was not allowed. */
    public enum Outcome { CREATED, UPDATED, MOVED, MOVE_PENDING }

    /** Upserts the Argo CD Application. dev/staging auto-sync; prod is manual (approval gate). */
    public Outcome upsertApp(Project p, String env) {
        return upsertApp(p, env, false, false);
    }

    /**
     * @param syncIfMoved      trigger a sync when the app's destination namespace or cluster changed;
     *                         needed for prod, which has no auto-sync
     * @param allowClusterMove allow re-homing an app that currently lives on a different cluster. Argo CD does not
     *                         clean up the old cluster when only the destination changes, so a move is
     *                         delete (cascade, removes the old workload) + create on the new cluster.
     */
    public Outcome upsertApp(Project p, String env, boolean syncIfMoved, boolean allowClusterMove) {
        String name = appName(p, env);
        Cluster target = clusters.clusterFor(env);
        Map<String, Object> existing = getApp(name, false);
        boolean moved = false;
        if (existing != null && !onCluster(existing, target)) {
            String from = describeDestination(existing);
            if (!allowClusterMove) {
                log.warn("{} is on {} but {} maps to {}; not moving it automatically", name, from, env, target.name());
                return Outcome.MOVE_PENDING;
            }
            log.info("moving {} from {} to {}", name, from, target.name());
            deleteAndWait(name);
            existing = null;
            moved = true;
        }
        String before = existing == null ? null : J.str(existing.get("spec"), "destination", "namespace");
        argo.post().uri("/api/v1/applications?upsert=true").body(appSpec(p, env, target, existing)).retrieve().toBodilessEntity();
        boolean nsChanged = before != null && !before.equals(ClusterService.namespace(p, env));
        // prod has no auto-sync; dev/staging pick the new app up themselves, but syncing now is harmless
        if (syncIfMoved && (moved || nsChanged)) sync(name);
        return moved ? Outcome.MOVED : existing == null ? Outcome.CREATED : Outcome.UPDATED;
    }

    Map<String, Object> appSpec(Project p, String env, Cluster target, Map<String, Object> existing) {
        var g = props.gitops();
        String chartToRoot = "../".repeat(g.chartPath().split("/").length);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("project", props.argocd().project());
        spec.put("source", Map.of(
                "repoURL", g.repoUrl(),
                "targetRevision", g.branch(),
                "path", g.chartPath(),
                "helm", Map.of(
                        "releaseName", p.name,
                        "valueFiles", List.of("values.yaml", chartToRoot + GitOpsService.valuesPath(g, p.name, env)))));
        // By cluster name (as registered in Argo CD), so a cluster can be re-registered with a new endpoint
        spec.put("destination", Map.of("name", target.name(), "namespace", ClusterService.namespace(p, env)));

        // Env namespaces (labels, pull secret, LimitRange) are prepared per cluster by bootstrap/add-cluster.sh
        Map<String, Object> syncPolicy = new LinkedHashMap<>();
        syncPolicy.put("syncOptions", List.of("CreateNamespace=false"));
        syncPolicy.put("retry", Map.of("limit", 3,
                "backoff", Map.of("duration", "10s", "factor", 2, "maxDuration", "3m")));
        // Existing apps keep the auto-sync choice made on the CD page; new apps get the defaults
        if (existing != null) {
            Object automated = J.get(existing.get("spec"), "syncPolicy", "automated");
            if (automated != null) syncPolicy.put("automated", automated);
        } else if (!"prod".equals(env)) {
            syncPolicy.put("automated", Map.of("prune", true, "selfHeal", true));
        }
        spec.put("syncPolicy", syncPolicy);

        return Map.of(
                "metadata", Map.of(
                        "name", appName(p, env),
                        "namespace", "argocd",
                        "labels", Map.of("aio.platform/project", p.name, "env", env, "aio.platform/cluster", target.name())),
                "spec", spec);
    }

    /** True when the app's destination (by name or server URL) is the target cluster. */
    static boolean onCluster(Map<String, Object> app, Cluster target) {
        String name = J.str(app.get("spec"), "destination", "name");
        String server = J.str(app.get("spec"), "destination", "server");
        if (name != null && !name.isBlank()) return name.equals(target.name());
        return server != null && server.equals(target.server());
    }

    private static String describeDestination(Map<String, Object> app) {
        String name = J.str(app.get("spec"), "destination", "name");
        return name != null && !name.isBlank() ? name : J.str(app.get("spec"), "destination", "server");
    }

    /** Cluster an existing app is deployed to (name, or server URL for apps created before multi-cluster). */
    public String currentCluster(Map<String, Object> app) {
        String name = J.str(app.get("spec"), "destination", "name");
        if (name != null && !name.isBlank()) return name;
        String server = J.str(app.get("spec"), "destination", "server");
        return clusters.clusters().stream().filter(c -> c.server() != null && c.server().equals(server))
                .map(Cluster::name).findFirst().orElse(server);
    }

    private void deleteAndWait(String name) {
        deleteApp(name);
        Instant until = Instant.now().plus(DELETE_WAIT);
        while (getApp(name, false) != null) {
            if (Instant.now().isAfter(until))
                throw new IllegalStateException(name + " is still being deleted from its old cluster - retry Repair in a minute");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while moving " + name);
            }
        }
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

    /** Live resource tree (Deployment, ReplicaSet, Pods, Service, Ingress...) or null if the app is missing. */
    public Map<String, Object> resourceTree(String name) {
        try {
            return argo.get().uri("/api/v1/applications/{n}/resource-tree", name).retrieve().body(MAP);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            return null;
        }
    }

    /** Hard refresh: re-reads Git and the cluster instead of using Argo's cache. */
    public void hardRefresh(String name) {
        argo.get().uri("/api/v1/applications/{n}?refresh=hard", name).retrieve().toBodilessEntity();
    }

    /** Turns auto-sync on (with the given prune / self-heal flags) or off, leaving the rest of syncPolicy alone. */
    public void setAutoSync(String name, boolean automated, boolean prune, boolean selfHeal) {
        Map<String, Object> app = getApp(name, false);
        if (app == null) throw new java.util.NoSuchElementException(name);
        Object current = J.get(app.get("spec"), "syncPolicy");
        Map<String, Object> policy = current instanceof Map<?, ?> m ? new LinkedHashMap<>(castMap(m)) : new LinkedHashMap<>();
        if (automated) policy.put("automated", Map.of("prune", prune, "selfHeal", selfHeal));
        else policy.put("automated", null);   // explicit null: a merge patch only removes keys set to null
        String patch = "{\"spec\":{\"syncPolicy\":" + JSON.valueToTree(policy) + "}}";
        argo.patch().uri("/api/v1/applications/{n}", name)
                .body(Map.of("name", name, "patch", patch, "patchType", "merge"))
                .retrieve().toBodilessEntity();
    }

    // ------------------------------------------------------------------ workload cluster access via Argo CD

    /** Identifies one live resource of an app (as in the resource tree). */
    public record Ref(String namespace, String group, String version, String kind, String name) {
        UriBuilder query(UriBuilder b) {
            return b.queryParam("namespace", namespace).queryParam("resourceName", name)
                    .queryParam("group", group == null ? "" : group).queryParam("version", version).queryParam("kind", kind);
        }
    }

    /** Pod logs from the workload cluster (last {@code tail} lines; {@code previous}: the crashed container). */
    public String logs(String app, String namespace, String pod, String container, boolean previous, int tail) {
        String body = argo.get().uri(b -> {
            b.path("/api/v1/applications/{n}/logs").queryParam("namespace", namespace).queryParam("podName", pod)
                    .queryParam("tailLines", tail).queryParam("follow", false).queryParam("previous", previous);
            if (container != null && !container.isBlank()) b.queryParam("container", container);
            return b.build(app);
        }).retrieve().body(String.class);
        return parseLogStream(body);
    }

    /** The logs endpoint streams one JSON object per line: {"result":{"content":"...","last":false}}. */
    static String parseLogStream(String body) {
        if (body == null || body.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                JsonNode n = JSON.readTree(line);
                JsonNode r = n.has("result") ? n.get("result") : n;
                if (r.hasNonNull("content") && !r.path("last").asBoolean(false)) out.append(r.get("content").asText()).append('\n');
                else if (n.has("error")) out.append("(").append(n.path("error").path("message").asText("log stream error")).append(")\n");
            } catch (Exception e) {
                out.append(line).append('\n');   // not JSON: pass through
            }
        }
        return out.toString();
    }

    /** Live manifest of one resource, or null when it does not exist. */
    public Map<String, Object> resource(String app, Ref ref) {
        try {
            Map<String, Object> res = argo.get().uri(b -> ref.query(b.path("/api/v1/applications/{n}/resource")).build(app))
                    .retrieve().body(MAP);
            String manifest = res == null ? null : J.str(res, "manifest");
            return manifest == null || manifest.isBlank() ? null : JSON.readValue(manifest, MAP_TYPE);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unreadable manifest for " + ref.kind() + "/" + ref.name());
        }
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    /** Runs a resource action (e.g. Rollout promote-full / abort / retry) on the workload cluster. */
    public void runAction(String app, Ref ref, String action) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", app);
        body.put("namespace", ref.namespace());
        body.put("resourceName", ref.name());
        body.put("group", ref.group() == null ? "" : ref.group());
        body.put("version", ref.version());
        body.put("kind", ref.kind());
        body.put("action", action);
        try {
            argo.post().uri("/api/v1/applications/{n}/resource/actions/v2", app).body(body).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.MethodNotAllowed e) {
            // older Argo CD: v1 endpoint takes the action name as a JSON string body
            argo.post().uri(b -> ref.query(b.path("/api/v1/applications/{n}/resource/actions")).build(app))
                    .body("\"" + action + "\"").retrieve().toBodilessEntity();
        }
    }

    /** Clusters registered in Argo CD with their connection state (empty list if the token may not list them). */
    public List<Map<String, Object>> listClusters() {
        try {
            return J.list(argo.get().uri("/api/v1/clusters").retrieve().body(MAP), "items");
        } catch (HttpClientErrorException.Forbidden e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** Deletes the Application and (cascade) everything it deployed. Missing apps are ignored. */
    public void deleteApp(String name) {
        try {
            argo.delete().uri("/api/v1/applications/{n}?cascade=true", name).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // already gone
        }
    }
}
