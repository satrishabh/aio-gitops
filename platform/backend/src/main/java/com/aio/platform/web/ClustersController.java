package com.aio.platform.web;

import com.aio.platform.config.AppProperties.Cluster;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.service.ArgoCdService;
import com.aio.platform.service.ClusterRegistry;
import com.aio.platform.service.J;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configured workload clusters joined with what Argo CD reports about them (connection, version, app count). */
@RestController
@RequestMapping("/api/clusters")
public class ClustersController {
    private final ClusterRegistry clusters;
    private final ArgoCdService argo;
    private final ProjectRepo projects;

    public ClustersController(ClusterRegistry clusters, ArgoCdService argo, ProjectRepo projects) {
        this.clusters = clusters;
        this.argo = argo;
        this.projects = projects;
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
