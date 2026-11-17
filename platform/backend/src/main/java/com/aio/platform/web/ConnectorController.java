package com.aio.platform.web;

import com.aio.platform.security.UserScope;
import com.aio.platform.service.AuditService;
import com.aio.platform.service.ConnectorCatalog;
import com.aio.platform.service.ConnectorService;
import java.net.URI;
import java.util.LinkedHashMap;
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
        m.put("links", Map.of("prometheus", grafanaUrl));
        m.put("connectors", connectors.list());
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

    @PostMapping("/{id}/disable")
    public Map<String, String> disable(@PathVariable String id, @AuthenticationPrincipal OAuth2User user) {
        requireAdmin();
        connectors.disable(id);
        audit.record(Me.login(user), "connector.disable", id, null);
        return Map.of("status", "removing");
    }

    private static void requireAdmin() {
        if (!UserScope.admin()) throw new SecurityException("Connectors are admin-only");
    }
}
