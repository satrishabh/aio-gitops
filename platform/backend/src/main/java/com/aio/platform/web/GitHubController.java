package com.aio.platform.web;

import com.aio.platform.service.GitHubClient;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Repo picker for the New project form, using the logged-in user's own GitHub token. */
@RestController
public class GitHubController {
    private final GitHubClient github;

    public GitHubController(GitHubClient github) {
        this.github = github;
    }

    @GetMapping("/api/github/repos")
    public List<Map<String, Object>> repos(@RegisteredOAuth2AuthorizedClient("github") OAuth2AuthorizedClient client,
                                           @AuthenticationPrincipal OAuth2User user) {
        Me.login(user);
        return github.listUserRepos(client.getAccessToken().getTokenValue()).stream()
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
