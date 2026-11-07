package com.aio.platform.web;

import com.aio.platform.service.GitHubClient;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** OIDC sessions do not contain a GitHub token: regular users see only their connected projects. */
@RestController
public class GitHubController {
    private final GitHubClient github;
    private final com.aio.platform.repo.ProjectRepo projects;

    public GitHubController(GitHubClient github, com.aio.platform.repo.ProjectRepo projects) {
        this.github = github;
        this.projects = projects;
    }

    @GetMapping("/api/github/repos")
    public List<Map<String, Object>> repos(@AuthenticationPrincipal OAuth2User user) {
        Me.login(user);
        if (!com.aio.platform.security.UserScope.admin()) return com.aio.platform.security.UserScope.projects(projects)
                .stream().map(project -> Map.<String, Object>of("fullName", project.repoOwner + "/" + project.repoName,
                        "url", project.repoUrl, "defaultBranch", project.defaultBranch,
                        "description", "Connected to " + project.name, "pushedAt", "")).distinct().toList();
        return github.listConfiguredRepos().stream()
                .filter(r -> !Boolean.TRUE.equals(r.get("archived")))
                .map(r -> Map.<String, Object>of(
                        "fullName", String.valueOf(r.get("full_name")),
                        "url", String.valueOf(r.get("html_url")),
                        "private", Boolean.TRUE.equals(r.get("private")),
                        "defaultBranch", String.valueOf(r.getOrDefault("default_branch", "main")),
                        "description", r.get("description") == null ? "" : String.valueOf(r.get("description")),
                        "pushedAt", String.valueOf(r.getOrDefault("pushed_at", ""))))
                .toList();
    }
}
