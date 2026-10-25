package com.aio.platform.web;

import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.service.TemplateService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/** Template gallery: AIO starters, your templates, live community search, and create-from-template. */
@RestController
public class TemplateController {
    private final TemplateService templates;
    private final ProjectRepo projects;

    public TemplateController(TemplateService templates, ProjectRepo projects) {
        this.templates = templates;
        this.projects = projects;
    }

    @GetMapping("/api/templates")
    public List<Map<String, Object>> list(@RequestParam(required = false) String source,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String language) {
        return templates.list(source, q, category, language);
    }

    @GetMapping("/api/templates/{owner}/{repo}/inspect")
    public TemplateService.Inspection inspect(@PathVariable String owner, @PathVariable String repo) {
        return templates.inspect(owner + "/" + repo);
    }

    @PostMapping("/api/templates/create")
    public Map<String, Object> create(@RequestBody TemplateService.CreateRequest req, @AuthenticationPrincipal OAuth2User user) {
        return templates.create(req, Me.login(user));
    }

    @PostMapping("/api/projects/{id}/save-as-template")
    public Map<String, Object> saveAsTemplate(@PathVariable Long id, @AuthenticationPrincipal OAuth2User user) {
        return templates.saveAsTemplate(projects.findById(id).orElseThrow(), Me.login(user));
    }
}
