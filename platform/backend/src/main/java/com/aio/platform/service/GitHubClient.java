package com.aio.platform.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Thin wrapper over the GitHub REST API (repo lookup, contents read/write, webhooks). */
@Service
public class GitHubClient {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private final RestClient gh;

    public GitHubClient(RestClient githubRestClient) {
        this.gh = githubRestClient;
    }

    public record FileContent(String content, String sha) {}

    public Map<String, Object> getRepo(String owner, String repo) {
        return gh.get().uri("/repos/{o}/{r}", owner, repo).retrieve().body(MAP);
    }

    public FileContent getFile(String owner, String repo, String path, String ref) {
        try {
            // path contains '/', so it is concatenated (inputs are validated) instead of passed as a URI variable
            Map<String, Object> body = gh.get()
                    .uri(contentsUri(owner, repo, path) + "?ref={ref}", ref)
                    .retrieve().body(MAP);
            String b64 = String.valueOf(body.get("content")).replaceAll("\\s", "");
            return new FileContent(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8),
                    String.valueOf(body.get("sha")));
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** Creates or updates a file in a single commit. {@code sha} must be the current blob sha when updating. */
    public void putFile(String owner, String repo, String path, String branch, String content, String sha, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("branch", branch);
        if (sha != null) body.put("sha", sha);
        gh.put().uri(contentsUri(owner, repo, path)).body(body).retrieve().toBodilessEntity();
    }

    private static String contentsUri(String owner, String repo, String path) {
        for (String s : new String[]{owner, repo, path}) {
            if (!s.matches("[A-Za-z0-9_./-]+") || s.contains("..")) throw new IllegalArgumentException("unsafe path: " + s);
        }
        return "/repos/" + owner + "/" + repo + "/contents/" + path;
    }

    public Long createWebhook(String owner, String repo, String url, String secret) {
        Map<String, Object> body = Map.of(
                "name", "web",
                "active", true,
                "events", List.of("push", "pull_request"),
                "config", Map.of("url", url, "content_type", "json", "secret", secret, "insecure_ssl", "0"));
        Map<String, Object> res = gh.post().uri("/repos/{o}/{r}/hooks", owner, repo).body(body).retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    throw new IllegalStateException("GitHub webhook creation failed: HTTP " + resp.getStatusCode());
                })
                .body(MAP);
        return ((Number) res.get("id")).longValue();
    }
}
