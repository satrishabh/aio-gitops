package com.aio.platform.web;

import com.aio.platform.security.UserScope;
import com.aio.platform.service.AuditService;
import com.aio.platform.service.ConnectorCatalog;
import com.aio.platform.service.ConnectorService;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/** Admin-only: open-source connectors (status, test, enable, disable, roll back). Every change is audited. */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {
    private final ConnectorService connectors;
    private final ConnectorCatalog catalog;
    private final AuditService audit;
    private final String grafanaUrl;

    public ConnectorController(ConnectorService connectors, ConnectorCatalog catalog, AuditService audit,
                               @Value("${app.tools.grafana-url:}") String grafanaUrl) {
        this.connectors = connectors;
        this.catalog = catalog;
        this.audit = audit;
        this.grafanaUrl = httpsLink(grafanaUrl);
    }

    /** A UI link shown to admins: HTTPS only, no credentials, query or fragment (empty when unset). */
    static String httpsLink(String value) {
        if (value == null || value.isBlank()) return "";
        URI uri = URI.create(value.trim());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("app.tools.grafana-url must be HTTPS without credentials, query or fragment");
        return value.trim().replaceAll("/+$", "");
    }

    public record VersionRequest(String version) {}

    @GetMapping
    public Map<String, Object> list() {
        requireAdmin();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cloud", catalog.cloud());
        m.put("site", catalog.site());
        m.put("catalog", catalog.source());
        m.put("links", Map.of("prometheus", grafanaUrl));
        m.put("connectors", connectors.list());
        m.put("phases", connectors.phases());
        return m;
    }

    /**
     * Switches every connector of a phase on (dependencies first) or off (reverse order). Lets a phase stay off until
     * every system it needs is ready, then go live in one step; switching off is the way back.
     */
    @PostMapping("/phases/{phase}/{state:on|off}")
    public Map<String, Object> switchPhase(@PathVariable String phase, @PathVariable String state,
                                           @RequestParam(defaultValue = "false") boolean force,
                                           @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        if (!phase.matches("[0-9A-Za-z.-]{1,10}")) throw new IllegalArgumentException("Invalid phase");
        boolean on = "on".equals(state);
        String actor = Me.login(user);
        List<ConnectorService.PhaseStep> steps = connectors.switchPhase(phase, on, actor, force);
        long failed = steps.stream().filter(s -> !s.ok()).count();
        audit.record(actor, "connector.phase-" + state, "phase " + phase + (force && !on ? " (forced)" : ""), steps.stream()
                .map(s -> s.id() + ": " + (s.ok() ? s.detail() : "failed (" + s.detail() + ")"))
                .collect(java.util.stream.Collectors.joining("; ")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("state", state);
        m.put("ok", failed == 0);
        m.put("steps", steps);
        return m;
    }

    @PostMapping("/{id}/test")
    public ConnectorService.TestResult test(@PathVariable String id) {
        requireAdmin();
        return connectors.test(id);
    }

    @PostMapping("/{id}/enable")
    public Map<String, String> enable(@PathVariable String id, @RequestBody(required = false) VersionRequest req,
                                      @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        String v = connectors.install(id, req == null ? null : req.version());
        audit.record(Me.login(user), "connector.enable", id, "version " + v);
        return Map.of("status", "installing", "version", v);
    }

    @PostMapping("/{id}/rollback")
    public Map<String, String> rollback(@PathVariable String id, @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        String v = connectors.rollback(id);
        audit.record(Me.login(user), "connector.rollback", id, "to version " + v);
        return Map.of("status", "rolling back", "version", v);
    }

    public Map<String, String> disable(String id, OAuth2User user) {
        return disable(id, false, user);
    }

    /**
     * Refused (409, message lists the projects) while projects use the connector through Project -> Services,
     * unless {@code force=true}: their services then stay in git but stop working.
     */
    @PostMapping("/{id}/disable")
    public Map<String, String> disable(@PathVariable String id, @RequestParam(defaultValue = "false") boolean force,
                                       @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        List<com.aio.platform.service.ServiceUsage.Use> users = force ? connectors.usedBy(id) : List.of();
        connectors.disable(id, force);
        audit.record(Me.login(user), "connector.disable", id, users.isEmpty() ? null
                : "forced while used by " + users.stream().map(u -> u.project() + " " + u.envs()).toList());
        return Map.of("status", "removing");
    }

    /** Projects (and their environments) that use this connector through Project -> Services. */
    @GetMapping("/{id}/usage")
    public Map<String, Object> usage(@PathVariable String id) {
        requireAdmin();
        catalog.find(id).orElseThrow();
        List<com.aio.platform.service.ServiceUsage.Use> users = connectors.usedBy(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connector", id);
        m.put("count", users.size());
        m.put("projects", users);
        return m;
    }

    private static void requireAdmin() {
        if (!UserScope.admin()) throw new SecurityException("Connectors are admin-only");
    }
}
