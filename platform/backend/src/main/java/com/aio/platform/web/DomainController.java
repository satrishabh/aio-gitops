package com.aio.platform.web;

import com.aio.platform.domain.Domain;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.service.ClusterRegistry;
import com.aio.platform.service.DomainService;
import com.aio.platform.service.DomainWatcher;
import com.aio.platform.service.GitOpsService;
import com.aio.platform.service.PromotionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/** Custom domains / path routes of a project, with what to put in DNS and certificate status. Prod is approver-only. */
@RestController
@RequestMapping("/api/projects/{id}/domains")
public class DomainController {
    private final ProjectRepo projects;
    private final DomainService domains;
    private final DomainWatcher watcher;
    private final ClusterRegistry clusters;
    private final PromotionService promotions;

    public DomainController(ProjectRepo projects, DomainService domains, DomainWatcher watcher, ClusterRegistry clusters,
                            PromotionService promotions) {
        this.projects = projects;
        this.domains = domains;
        this.watcher = watcher;
        this.clusters = clusters;
        this.promotions = promotions;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable Long id) {
        Project p = projects.findById(id).orElseThrow();
        Map<String, Object> envs = new LinkedHashMap<>();
        for (String env : GitOpsService.ENVS) {
            var c = clusters.clusterFor(env);
            envs.put(env, Map.of(
                    "cluster", c.name(),
                    "ingressIp", c.ingressIp() == null ? "" : c.ingressIp(),
                    "dnsZone", c.dnsZone() == null ? "" : c.dnsZone(),
                    "tls", clusters.tlsFor(env)));
        }
        return Map.of("domains", domains.list(p).stream().map(this::view).toList(), "environments", envs);
    }

    @PostMapping
    public Map<String, Object> add(@PathVariable Long id, @RequestBody DomainService.AddRequest req,
                                   @AuthenticationPrincipal OAuth2User user) {
        Project p = projects.findById(id).orElseThrow();
        String actor = guard(req == null ? null : req.env(), user);
        return view(domains.add(p, req, actor));
    }

    @DeleteMapping("/{domainId}")
    public Map<String, String> remove(@PathVariable Long id, @PathVariable Long domainId, @AuthenticationPrincipal OAuth2User user) {
        Project p = projects.findById(id).orElseThrow();
        Domain d = domains.list(p).stream().filter(x -> x.id.equals(domainId)).findFirst().orElseThrow();
        domains.remove(p, domainId, guard(d.env, user));
        return Map.of("status", "removed");
    }

    /** "Check now": re-runs the DNS / certificate check instead of waiting for the next poll. */
    @PostMapping("/{domainId}/check")
    public Map<String, Object> check(@PathVariable Long id, @PathVariable Long domainId,
                                    @AuthenticationPrincipal OAuth2User user) {
        Project p = projects.findById(id).orElseThrow();
        Domain d = domains.list(p).stream().filter(x -> x.id.equals(domainId)).findFirst().orElseThrow();
        guard(d.env, user);
        return view(watcher.check(d));
    }

    private Map<String, Object> view(Domain d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("env", d.env);
        m.put("host", d.host);
        m.put("path", d.path);
        m.put("url", d.url());
        m.put("stripPrefix", d.stripPrefix);
        m.put("forceHttps", d.forceHttps);
        m.put("wwwRedirect", d.wwwRedirect);
        m.put("certOwner", d.certOwner);
        m.put("status", d.status);
        m.put("message", d.message);
        m.put("issuer", d.issuer);
        m.put("managedDns", clusters.managedDns(d.env, d.host));
        // What to create at the DNS provider (shown with a copy button); www too when redirecting
        m.put("dnsRecords", d.dnsExpected == null ? List.of() : d.wwwRedirect
                ? List.of(Map.of("type", "A", "name", d.host, "value", d.dnsExpected), Map.of("type", "A", "name", "www." + d.host, "value", d.dnsExpected))
                : List.of(Map.of("type", "A", "name", d.host, "value", d.dnsExpected)));
        m.put("certExpiresAt", d.certExpiresAt);
        m.put("lastCheckedAt", d.lastCheckedAt);
        m.put("createdBy", d.createdBy);
        m.put("createdAt", d.createdAt);
        return m;
    }

    private String guard(String env, OAuth2User user) {
        String actor = Me.login(user);
        if ("prod".equals(env) && !promotions.isApprover(actor)) throw new SecurityException("only approvers can change prod domains");
        return actor;
    }
}
