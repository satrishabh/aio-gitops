package com.aio.platform.web;

import com.aio.platform.security.UserScope;
import com.aio.platform.service.EnvironmentRegistry;
import com.aio.platform.service.EnvironmentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/**
 * The delivery environments in order (every tier reads them: pipelines, CD and Overview render from this), and the
 * admin actions to add or remove one and to re-apply its cluster's ingress settings to every project.
 */
@RestController
@RequestMapping("/api/environments")
public class EnvironmentsController {
    private final EnvironmentRegistry environments;
    private final EnvironmentService service;

    public EnvironmentsController(EnvironmentRegistry environments, EnvironmentService service) {
        this.environments = environments;
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return environments.list().stream().map(this::view).toList();
    }

    Map<String, Object> view(EnvironmentRegistry.Env e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name());
        m.put("position", e.position());
        m.put("promoteFrom", e.promoteFrom());
        m.put("cluster", e.cluster());
        m.put("namespace", e.namespace());
        m.put("approvalRequired", e.approvalRequired() || e.prodLike());
        m.put("prodLike", e.prodLike());
        m.put("spotAllowed", e.spotAllowed());
        var c = environments.cluster(e.cluster()).orElse(null);
        m.put("cloud", c == null ? null : c.cloud);
        m.put("clusterStatus", c == null ? null : c.status);
        m.put("baseDomain", c == null ? null : c.baseDomain);
        return m;
    }

    @PostMapping
    public EnvironmentService.Result add(@RequestBody EnvironmentService.AddRequest req, @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        return service.add(req, Me.login(user));
    }

    @DeleteMapping("/{name}")
    public EnvironmentService.Result remove(@PathVariable String name, @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        return service.remove(name, Me.login(user));
    }

    /** Re-apply the cluster's ingress class / controller / default host to every project's values (dryRun: preview). */
    @PostMapping("/{name}/reapply-cluster-settings")
    public Map<String, Object> reapply(@PathVariable String name, @RequestParam(defaultValue = "true") boolean dryRun,
                                       @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        return service.reapplyClusterSettings(name, dryRun, Me.login(user));
    }

    private static void requireAdmin() {
        if (!UserScope.admin()) throw new SecurityException("environments are managed by admins");
    }
}
