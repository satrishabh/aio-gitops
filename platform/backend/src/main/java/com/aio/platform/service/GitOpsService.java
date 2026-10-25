package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Owns the per-project Helm values files in the GitOps repo:
 * {@code <apps-path>/<project>/values-<env>.yaml}. Argo CD watches these files;
 * changing {@code image.tag} is how every deploy, promotion and rollback happens.
 *
 * <p>New environments start at {@code replicaCount: 0} with the placeholder tag, so nothing is
 * pulled (and nothing shows Degraded) until a real image is deployed; {@link #setTag} scales 0 -> 1.
 */
@Service
public class GitOpsService {
    public static final List<String> ENVS = List.of("dev", "staging", "prod");
    public static final String PLACEHOLDER_TAG = "0.0.0";
    public static final String PULL_SECRET = "ghcr-pull";

    private static final Pattern TAG_LINE = Pattern.compile("(?m)^(\\s+tag:\\s*).*$");
    private static final Pattern TAG_VALUE = Pattern.compile("(?m)^\\s+tag:\\s*[\"']?([^\"'\\s#]+)[\"']?");
    private static final Pattern REPLICAS_LINE = Pattern.compile("(?m)^replicaCount:\\s*(\\d+)\\s*$");
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final GitHubClient github;
    private final AppProperties props;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public GitOpsService(GitHubClient github, AppProperties props) {
        this.github = github;
        this.props = props;
    }

    /** Parsed view of a values file. */
    public record EnvValues(String tag, Integer replicas, String appUrl) {}

    private record Cached(EnvValues values, Instant at) {}

    public static String valuesPath(AppProperties.GitOps g, String project, String env) {
        return g.appsPath() + "/" + project + "/values-" + env + ".yaml";
    }

    /** Creates values-<env>.yaml for each environment if missing. */
    public void initProject(Project p) {
        var g = props.gitops();
        for (String env : ENVS) {
            String path = valuesPath(g, p.name, env);
            if (github.getFile(g.owner(), g.repo(), path, g.branch()) == null) {
                github.putFile(g.owner(), g.repo(), path, g.branch(), initialValues(p, env), null,
                        "platform: init " + p.name + " " + env);
            }
        }
    }

    /**
     * Brings existing values files up to the current conventions (pull secret, 0 replicas while
     * on the placeholder tag). Safe to call repeatedly; only commits when something changes.
     */
    public void reconcile(Project p) {
        var g = props.gitops();
        for (String env : ENVS) {
            String path = valuesPath(g, p.name, env);
            var file = github.getFile(g.owner(), g.repo(), path, g.branch());
            if (file == null) continue;
            String updated = normalize(file.content());
            if (!updated.equals(file.content())) {
                github.putFile(g.owner(), g.repo(), path, g.branch(), updated, file.sha(),
                        "platform: reconcile " + p.name + " " + env + " values");
                cache.remove(p.name + "/" + env);
            }
        }
    }

    public String readTag(Project p, String env) {
        var g = props.gitops();
        var file = github.getFile(g.owner(), g.repo(), valuesPath(g, p.name, env), g.branch());
        if (file == null) throw new IllegalStateException("values file missing for " + p.name + "/" + env);
        return extractTag(file.content());
    }

    /** Cached (60s) read used by the status poller to stay well within GitHub API limits. */
    public EnvValues readValuesCached(Project p, String env) {
        String key = p.name + "/" + env;
        Cached c = cache.get(key);
        if (c != null && c.at().plus(CACHE_TTL).isAfter(Instant.now())) return c.values();
        var g = props.gitops();
        var file = github.getFile(g.owner(), g.repo(), valuesPath(g, p.name, env), g.branch());
        EnvValues v = file == null ? new EnvValues(null, null, null) : parse(file.content());
        cache.put(key, new Cached(v, Instant.now()));
        return v;
    }

    public void setTag(Project p, String env, String tag, String message) {
        var g = props.gitops();
        String path = valuesPath(g, p.name, env);
        var file = github.getFile(g.owner(), g.repo(), path, g.branch());
        if (file == null) throw new IllegalStateException("values file missing: " + path);
        String updated = ensureRunning(normalize(replaceTag(file.content(), tag)));
        cache.remove(p.name + "/" + env);
        if (updated.equals(file.content())) return;
        github.putFile(g.owner(), g.repo(), path, g.branch(), updated, file.sha(), message);
    }

    public void deleteProjectFiles(Project p) {
        var g = props.gitops();
        for (String env : ENVS) {
            String path = valuesPath(g, p.name, env);
            var file = github.getFile(g.owner(), g.repo(), path, g.branch());
            if (file != null) {
                github.deleteFile(g.owner(), g.repo(), path, g.branch(), file.sha(), "platform: delete " + p.name + " " + env);
            }
            cache.remove(p.name + "/" + env);
        }
    }

    // ------------------------------------------------------------------ pure helpers (unit tested)

    static String extractTag(String yaml) {
        Matcher m = TAG_VALUE.matcher(yaml);
        return m.find() ? m.group(1) : null;
    }

    static Integer extractReplicas(String yaml) {
        Matcher m = REPLICAS_LINE.matcher(yaml);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    static EnvValues parse(String yaml) {
        // Walk the top-level "ingress:" block: url only when enabled: true and a real host is set
        boolean inIngress = false, enabled = false;
        String host = null;
        for (String line : yaml.split("\\R")) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) {
                inIngress = line.startsWith("ingress:");
                continue;
            }
            if (!inIngress) continue;
            String t = line.trim();
            if (t.startsWith("enabled:")) enabled = t.substring(8).trim().startsWith("true");
            if (t.startsWith("host:")) host = t.substring(5).trim().replaceAll("[\"']", "");
        }
        String url = enabled && host != null && !host.isEmpty() && !"example.local".equals(host) ? "http://" + host : null;
        return new EnvValues(extractTag(yaml), extractReplicas(yaml), url);
    }

    static String replaceTag(String yaml, String tag) {
        if (!tag.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("invalid image tag: " + tag);
        Matcher m = TAG_LINE.matcher(yaml);
        if (!m.find()) throw new IllegalStateException("no image.tag line in values file");
        return yaml.substring(0, m.start()) + m.group(1) + "\"" + tag + "\"" + yaml.substring(m.end());
    }

    static String setReplicas(String yaml, int n) {
        Matcher m = REPLICAS_LINE.matcher(yaml);
        if (m.find()) return yaml.substring(0, m.start()) + "replicaCount: " + n + yaml.substring(m.end());
        return yaml.endsWith("\n") ? yaml + "replicaCount: " + n + "\n" : yaml + "\nreplicaCount: " + n + "\n";
    }

    /** A real image is being deployed: scale from 0 to 1 (higher counts are left alone). */
    static String ensureRunning(String yaml) {
        Integer r = extractReplicas(yaml);
        return r == null || r == 0 ? setReplicas(yaml, 1) : yaml;
    }

    /** Adds the pull secret if missing; parks placeholder envs at 0 replicas. */
    static String normalize(String yaml) {
        String out = yaml;
        if (!out.contains("imagePullSecrets:")) {
            out = out.endsWith("\n") ? out : out + "\n";
            out = out + "imagePullSecrets:\n  - name: " + PULL_SECRET + "\n";
        }
        if (PLACEHOLDER_TAG.equals(extractTag(out))) out = setReplicas(out, 0);
        return out;
    }

    private String initialValues(Project p, String env) {
        String domain = props.baseDomain();
        boolean ingress = domain != null && !domain.isBlank();
        String host = "prod".equals(env) ? p.name + "." + domain : p.name + "-" + env + "." + domain;
        return """
                # Managed by AIO platform - image.tag is updated by CI / promotions
                image:
                  repository: %s
                  tag: "%s"
                # 0 until a real image is deployed (avoids pulling the placeholder tag)
                replicaCount: 0
                imagePullSecrets:
                  - name: %s
                pdb:
                  enabled: false
                ingress:
                  enabled: %s
                  host: %s
                """.formatted(p.imageRepo, PLACEHOLDER_TAG, PULL_SECRET, ingress, ingress ? host : "example.local");
    }
}
