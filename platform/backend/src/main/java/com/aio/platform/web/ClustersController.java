package com.aio.platform.web;

import com.aio.platform.config.AppProperties.Cluster;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.security.UserScope;
import com.aio.platform.service.ArgoCdService;
import com.aio.platform.service.ClusterAccess;
import com.aio.platform.service.ClusterAdminService;
import com.aio.platform.service.ClusterRegistry;
import com.aio.platform.service.EnvironmentRegistry;
import com.aio.platform.service.J;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/**
 * Clusters (admin): each one joined with what Argo CD reports (connection, version, app count) and the platform's own
 * 5-minute health check; Add cluster (describe → bootstrap script → paste token), Rotate token, Remove, Check now.
 */
@RestController
@RequestMapping("/api/clusters")
public class ClustersController {
    private final ClusterRegistry clusters;
    private final ArgoCdService argo;
    private final ProjectRepo projects;
    private final EnvironmentRegistry environments;
    private final ClusterAdminService admin;
    private final ClusterAccess access;

    public ClustersController(ClusterRegistry clusters, ArgoCdService argo, ProjectRepo projects,
                              EnvironmentRegistry environments, ClusterAdminService admin, ClusterAccess access) {
        this.clusters = clusters;
        this.argo = argo;
        this.projects = projects;
        this.environments = environments;
        this.admin = admin;
        this.access = access;
    }

    /** Step 2: the bootstrap script for a new cluster (or, with {@code rotate}, for an existing one). No secrets. */
    @PostMapping("/bootstrap")
    public Map<String, String> bootstrap(@RequestBody ClusterAdminService.ClusterRequest req,
                                         @RequestParam(defaultValue = "false") boolean rotate) {
        requireAdmin();
        return admin.bootstrap(req, rotate);
    }

    /** Step 3: register the cluster in Argo CD with the pasted token, test it, store it (no credentials). */
    @PostMapping
    public ClusterAdminService.ConnectResult connect(@RequestBody ClusterAdminService.ClusterRequest req,
                                                     @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        return admin.connect(req, Me.login(user));
    }

    @PostMapping("/{name}/token")
    public ClusterAdminService.ConnectResult rotate(@PathVariable String name, @RequestBody ClusterAdminService.ClusterRequest req,
                                                    @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        return admin.rotate(name, req, Me.login(user));
    }

    @PostMapping("/{name}/check")
    public Map<String, Object> check(@PathVariable String name) {
        requireAdmin();
        var c = admin.check(name);
        return Map.of("name", c.name, "health", c.status, "error", c.lastError == null ? "" : c.lastError);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> remove(@PathVariable String name, @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        return admin.remove(name, Me.login(user));
    }

    private static void requireAdmin() {
        if (!UserScope.admin()) throw new SecurityException("clusters are managed by admins");
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> registered;
        String argoError = null;
        try {
            registered = argo.listClusters();
        } catch (RuntimeException e) {
            registered = List.of();
            argoError = "Argo CD unreachable: " + e.getMessage();
        }
        long projectCount = projects.count();
        final List<Map<String, Object>> reg = registered;
        final String err = argoError;
        return clusters.clusters().stream().map(c -> {
            Map<String, Object> a = match(reg, c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("server", c.server());
            m.put("envs", c.envs());
            m.put("baseDomain", c.baseDomain());
            m.put("ingressIp", c.ingressIp());
            m.put("issuer", c.issuer() == null ? ClusterRegistry.DEFAULT_ISSUER : c.issuer());
            m.put("dnsZone", c.dnsZone());
            m.put("tls", Boolean.TRUE.equals(c.tls()));
            m.put("ingressClass", c.ingressClass() == null ? "nginx" : c.ingressClass());
            m.put("ingressController", c.ingressController() == null ? "nginx" : c.ingressController());
            m.put("rollouts", c.rollouts() != null ? c.rollouts()
                    : clusters.inCluster(c) && !c.envs().isEmpty() && clusters.rolloutsFor(c.envs().get(0)));
            m.put("hub", clusters.inCluster(c));
            m.put("registered", a != null);
            m.put("status", a == null ? (err != null ? "Unknown" : "NotRegistered")
                    : first(J.str(a, "connectionState", "status"), J.str(a, "info", "connectionState", "status"), "Unknown"));
            m.put("message", a == null ? err
                    : first(J.str(a, "connectionState", "message"), J.str(a, "info", "connectionState", "message"), null));
            m.put("version", a == null ? null : first(J.str(a, "serverVersion"), J.str(a, "info", "serverVersion"), null));
            m.put("argoApps", a == null ? null : J.get(a, "info", "applicationsCount"));
            m.put("platformApps", projectCount * c.envs().size());   // one app per project per hosted env
            var row = environments.cluster(c.name()).orElse(null);
            m.put("cloud", row == null ? null : row.cloud);
            m.put("ingressNamespace", c.ingressNamespace());
            m.put("health", row == null ? null : row.status);
            m.put("healthError", row == null ? null : row.lastError);
            m.put("checkedAt", row == null ? null : row.lastCheckAt);
            m.put("createdBy", row == null ? null : row.createdBy);
            boolean managed;
            try { managed = !clusters.inCluster(c) && access.hasSecret(c.name()); }
            catch (RuntimeException e) { managed = false; }
            m.put("managed", managed);   // added from the portal: token can be rotated, cluster removed
            return m;
        }).toList();
    }

    /** Argo CD's entry for a configured cluster: by name, else by server URL. */
    private static Map<String, Object> match(List<Map<String, Object>> registered, Cluster c) {
        return registered.stream().filter(a -> c.name().equals(J.str(a, "name"))).findFirst()
                .or(() -> registered.stream().filter(a -> c.server() != null && c.server().equals(J.str(a, "server"))).findFirst())
                .orElse(null);
    }

    private static String first(String a, String b, String fallback) {
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : fallback;
    }
}
