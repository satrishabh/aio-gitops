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
    private final String apiUrl;

    public GitHubClient(RestClient githubRestClient, com.aio.platform.config.AppProperties props) {
        this.gh = githubRestClient;
        this.apiUrl = props.github().apiUrl();
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

    public void deleteWebhook(String owner, String repo, long hookId) {
        try {
            gh.delete().uri("/repos/{o}/{r}/hooks/{id}", owner, repo, hookId).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // already gone
        }
    }

    public void deleteFile(String owner, String repo, String path, String branch, String sha, String message) {
        Map<String, Object> body = Map.of("message", message, "sha", sha, "branch", branch);
        gh.method(org.springframework.http.HttpMethod.DELETE).uri(contentsUri(owner, repo, path))
                .body(body).retrieve().toBodilessEntity();
    }

    public boolean repoExists(String owner, String repo) {
        try {
            getRepo(owner, repo);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    /** Creates {@code owner/name} from a template repository (only works when the source has is_template=true). */
    public Map<String, Object> generateFromTemplate(String templateOwner, String templateRepo, String owner, String name,
                                                    boolean privateRepo, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("owner", owner);
        body.put("name", name);
        body.put("private", privateRepo);
        body.put("include_all_branches", false);
        if (description != null && !description.isBlank()) body.put("description", description);
        return gh.post().uri("/repos/{o}/{r}/generate", templateOwner, templateRepo).body(body).retrieve().body(MAP);
    }

    /** GitHub repository search, best match by stars. {@code q} is passed as a URI variable, so it is encoded. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchRepos(String q, int perPage) {
        Map<String, Object> res = gh.get()
                .uri("/search/repositories?q={q}&sort=stars&order=desc&per_page={n}", q, perPage)
                .retrieve().body(MAP);
        Object items = res == null ? null : res.get("items");
        return items instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /** Names of the files and folders at the repository root ({@code null} if the repo is empty or not ready yet). */
    public List<String> rootEntries(String owner, String repo) {
        try {
            List<Map<String, Object>> entries = gh.get().uri("/repos/{o}/{r}/contents", owner, repo).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return entries == null ? null : entries.stream().map(e -> String.valueOf(e.get("name"))).toList();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public void setTemplate(String owner, String repo, boolean isTemplate) {
        gh.patch().uri("/repos/{o}/{r}", owner, repo).body(Map.of("is_template", isTemplate)).retrieve().toBodilessEntity();
    }

    public void replaceTopics(String owner, String repo, List<String> topics) {
        gh.put().uri("/repos/{o}/{r}/topics", owner, repo).body(Map.of("names", topics)).retrieve().toBodilessEntity();
    }

    /** Repos visible to the logged-in user (uses their OAuth token, not the platform PAT). */
    public List<Map<String, Object>> listUserRepos(String userToken) {
        List<Map<String, Object>> repos = RestClient.create(apiUrl).get()
                .uri("/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member")
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .header(org.springframework.http.HttpHeaders.ACCEPT, "application/vnd.github+json")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return repos == null ? List.of() : repos;
    }
}
