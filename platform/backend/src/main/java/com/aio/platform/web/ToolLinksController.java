package com.aio.platform.web;

import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.security.UserScope;
import com.aio.platform.service.ArgoCdService;
import com.aio.platform.service.GitOpsService;
import com.aio.platform.config.AppProperties;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class ToolLinksController {
    private final ProjectRepo projects;
    private final AppProperties props;
    private final String argoUrl;
    private final String tektonUrl;
    public ToolLinksController(ProjectRepo projects, AppProperties props,
            @Value("${app.tools.argocd-url:}") String argoUrl, @Value("${app.tools.tekton-url:}") String tektonUrl) {
        this.projects = projects; this.props = props; this.argoUrl = validated(argoUrl); this.tektonUrl = validated(tektonUrl);
    }
    @GetMapping("/api/projects/{id}/tools")
    public Map<String, Object> links(@PathVariable Long id) {
        var project = UserScope.project(projects, id);
        Map<String, String> applications = new LinkedHashMap<>();
        if (!argoUrl.isEmpty()) for (String env : GitOpsService.ENVS)
            applications.put(env, argoUrl + "/applications/argocd/" + ArgoCdService.appName(project, env));
        // Until per-user Kubernetes authorization is deployed, Dashboard is operator-only.
        return Map.of("argocd", applications, "tekton", UserScope.admin() ? tektonUrl : "");
    }
    static String validated(String value) {
        if (value == null || value.isBlank()) return "";
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Tool UI URLs must be HTTPS without credentials, query, or fragment");
        return value.replaceAll("/+$", "");
    }
}
