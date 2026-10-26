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
    private final RolloutService rollouts;
    private final ClusterRegistry clusters;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public GitOpsService(GitHubClient github, AppProperties props, RolloutService rollouts, ClusterRegistry clusters) {
        this.github = github;
        this.props = props;
        this.rollouts = rollouts;
        this.clusters = clusters;
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
     *
     * @param retarget envs whose app is (or is about to be) on its configured cluster - only those get their
     *                 ingress hosts moved to that cluster's domain, so a not-yet-moved prod keeps working
     */
    public void reconcile(Project p, java.util.Set<String> retarget) {
        var g = props.gitops();
        for (String env : ENVS) {
            String path = valuesPath(g, p.name, env);
            var file = github.getFile(g.owner(), g.repo(), path, g.branch());
            if (file == null) continue;
            String updated = normalize(file.content());
            if (retarget.contains(env)) updated = withDefaultTls(retargetHosts(updated, p, env), p, env);
            // installed() describes prod's configured cluster, so only enable blue-green once prod lives there
            if (RolloutService.ENV.equals(env) && retarget.contains(env) && rollouts.installed()) updated = withBlueGreen(updated, previewHost(p));
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

    /** Rewrites env: and resources: in every environment's values file; returns the envs that changed. */
    public List<String> applyRuntime(Project p, String message) {
        var g = props.gitops();
        List<String> changed = new java.util.ArrayList<>();
        for (String env : ENVS) {
            String path = valuesPath(g, p.name, env);
            var file = github.getFile(g.owner(), g.repo(), path, g.branch());
            if (file == null) continue;
            String updated = RuntimeConfig.apply(file.content(), p);
            if (updated.equals(file.content())) continue;
            github.putFile(g.owner(), g.repo(), path, g.branch(), updated, file.sha(), message + " (" + env + ")");
            cache.remove(p.name + "/" + env);
            changed.add(env);
        }
        return changed;
    }

    /** Writes the routes: block (custom domains / path routes) of one env; returns false when nothing changed. */
    public boolean applyRoutes(Project p, String env, String routesBlock, String message) {
        var g = props.gitops();
        String path = valuesPath(g, p.name, env);
        var file = github.getFile(g.owner(), g.repo(), path, g.branch());
        if (file == null) throw new IllegalStateException("values file missing: " + path);
        String updated = RuntimeConfig.setBlock(file.content(), "routes", routesBlock);
        cache.remove(p.name + "/" + env);
        if (updated.equals(file.content())) return false;
        github.putFile(g.owner(), g.repo(), path, g.branch(), updated, file.sha(), message);
        return true;
    }

    /**
     * HTTPS on the generated default host when the env's cluster has cert-manager ({@code tls: true}): sets
     * ingress.tls / issuer / forceHttps / externalDns inside the ingress block. Off leaves the file untouched,
     * so clusters without cert-manager keep plain http.
     */
    String withDefaultTls(String yaml, Project p, String env) {
        if (!clusters.tlsFor(env)) return yaml;
        String domain = clusters.baseDomainFor(env);
        if (domain == null) return yaml;
        String host = hostOn(p, env, domain);
        String out = setIngressKey(yaml, "tls", "true");
        out = setIngressKey(out, "issuer", clusters.issuerFor(env, host));
        out = setIngressKey(out, "forceHttps", "true");
        return setIngressKey(out, "externalDns", String.valueOf(clusters.managedDns(env, host)));
    }

    /** Sets {@code key: value} inside the top-level ingress: block (replacing or appending the line). */
    static String setIngressKey(String yaml, String key, String value) {
        Matcher block = Pattern.compile("(?m)^ingress:.*\\R((?:[ \\t]+.*(?:\\R|$))*)").matcher(yaml);
        if (!block.find()) return yaml;
        String body = block.group(1);
        Matcher line = Pattern.compile("(?m)^([ \\t]+)" + Pattern.quote(key) + ":.*$").matcher(body);
        String newBody;
        if (line.find()) {
            newBody = body.substring(0, line.start()) + line.group(1) + key + ": " + value + body.substring(line.end());
        } else {
            String sep = body.isEmpty() || body.endsWith("\n") ? "" : "\n";
            newBody = body + sep + "  " + key + ": " + value + "\n";
        }
        return yaml.substring(0, block.start(1)) + newBody + yaml.substring(block.end(1));
    }

    /** Raw values file (as edited on the CD page) with its blob sha for optimistic locking. */
    public record RawValues(String path, String yaml, String sha) {}

    public RawValues readRaw(Project p, String env) {
        var g = props.gitops();
        String path = valuesPath(g, p.name, env);
        var file = github.getFile(g.owner(), g.repo(), path, g.branch());
        if (file == null) throw new IllegalStateException("values file missing: " + path);
        return new RawValues(path, file.content(), file.sha());
    }

    /**
     * Commits a hand-edited values file. {@code sha} is the version the user started from; if someone
     * committed since, the save is refused instead of overwriting their change.
     */
    public void writeRaw(Project p, String env, String yaml, String sha, String message) {
        validateValues(yaml, p.imageRepo);
        var g = props.gitops();
        String path = valuesPath(g, p.name, env);
        var file = github.getFile(g.owner(), g.repo(), path, g.branch());
        if (file == null) throw new IllegalStateException("values file missing: " + path);
        if (sha == null || !sha.equals(file.sha()))
            throw new IllegalStateException("values-" + env + ".yaml changed since you opened it - reload and re-apply your edit");
        cache.remove(p.name + "/" + env);
        if (yaml.equals(file.content())) return;
        github.putFile(g.owner(), g.repo(), path, g.branch(), yaml, file.sha(), message);
    }

    /** Must be a YAML mapping with a valid image.tag, keep the project's image repository, and sane replicas. */
    static void validateValues(String yaml, String imageRepo) {
        if (yaml == null || yaml.isBlank()) throw new IllegalArgumentException("values file is empty");
        if (yaml.length() > 64_000) throw new IllegalArgumentException("values file is too large");
        Object doc;
        try {
            doc = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                    new org.yaml.snakeyaml.LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid YAML: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        }
        if (!(doc instanceof Map<?, ?>)) throw new IllegalArgumentException("values file must be a YAML mapping");
        String tag = J.str(doc, "image", "tag");
        if (tag == null || !tag.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("image.tag is missing or invalid");
        String repo = J.str(doc, "image", "repository");
        if (repo != null && imageRepo != null && !repo.equals(imageRepo))
            throw new IllegalArgumentException("image.repository must stay " + imageRepo);
        Object replicas = J.get(doc, "replicaCount");
        if (replicas != null && !(replicas instanceof Integer n && n >= 0 && n <= 10))
            throw new IllegalArgumentException("replicaCount must be a whole number from 0 to 10");
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
        boolean inIngress = false, enabled = false, tls = false;
        String host = null;
        for (String line : yaml.split("\\R")) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) {
                inIngress = line.startsWith("ingress:");
                continue;
            }
            if (!inIngress) continue;
            String t = line.trim();
            if (t.startsWith("enabled:")) enabled = t.substring(8).trim().startsWith("true");
            if (t.startsWith("tls:")) tls = t.substring(4).trim().startsWith("true");
            if (t.startsWith("host:")) host = t.substring(5).trim().replaceAll("[\"']", "");
        }
        String url = enabled && host != null && !host.isEmpty() && !"example.local".equals(host) ? (tls ? "https://" : "http://") + host : null;
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

    /** A real image is being deployed: scale from 0 to 1 unless someone scaled the env down on purpose. */
    static String ensureRunning(String yaml) {
        if (isScaledDown(yaml)) return yaml;
        Integer r = extractReplicas(yaml);
        return r == null || r == 0 ? setReplicas(yaml, 1) : yaml;
    }

    private static final Pattern SCALED_DOWN_LINE = Pattern.compile("(?m)^scaledDown:\\s*\\S*\\s*\\R?");

    static boolean isScaledDown(String yaml) {
        return Pattern.compile("(?m)^scaledDown:\\s*true\\s*$").matcher(yaml).find();
    }

    /**
     * Sets replicaCount; n=0 also writes {@code scaledDown: true} (ignored by the chart) so later deploys
     * update the image without waking the app up. Any n>0 clears the marker.
     */
    static String scale(String yaml, int n) {
        String out = SCALED_DOWN_LINE.matcher(setReplicas(yaml, n)).replaceAll("");
        if (n == 0) out = (out.endsWith("\n") ? out : out + "\n") + "scaledDown: true\n";
        return out;
    }

    /** Commits a replica change for one environment; returns false when nothing changed. */
    public boolean setReplicas(Project p, String env, int n, String message) {
        var g = props.gitops();
        String path = valuesPath(g, p.name, env);
        var file = github.getFile(g.owner(), g.repo(), path, g.branch());
        if (file == null) throw new IllegalStateException("values file missing: " + path);
        if (n > 0 && PLACEHOLDER_TAG.equals(extractTag(file.content())))
            throw new IllegalArgumentException(env + " has no deployed image yet - deploy before starting it");
        String updated = scale(file.content(), n);
        cache.remove(p.name + "/" + env);
        if (updated.equals(file.content())) return false;
        github.putFile(g.owner(), g.repo(), path, g.branch(), updated, file.sha(), message);
        return true;
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

    /** Turns on blue-green (Argo Rollouts) in a values file; keeps an existing block (e.g. hand-tuned) untouched. */
    static String withBlueGreen(String yaml, String previewHost) {
        if (Pattern.compile("(?m)^blueGreen:").matcher(yaml).find()) return yaml;
        return RuntimeConfig.setBlock(yaml, "blueGreen", "blueGreen:\n  enabled: true\n"
                + (previewHost == null ? "" : "  previewHost: " + previewHost + "\n"));
    }

    private String previewHost(Project p) {
        String domain = clusters.baseDomainFor(RolloutService.ENV);
        return domain == null ? null : p.name + "-preview." + domain;
    }

    /** Platform-generated ingress host of an env on a given domain: prod is {@code <project>.<domain>}. */
    static String hostOn(Project p, String env, String domain) {
        return "prod".equals(env) ? p.name + "." + domain : p.name + "-" + env + "." + domain;
    }

    /**
     * When an env moves to another cluster its hosts must move to that cluster's ingress domain. Only hosts the
     * platform generated (for a known old domain) are rewritten; a custom host set on the CD page is left alone.
     */
    String retargetHosts(String yaml, Project p, String env) {
        String to = clusters.baseDomainFor(env);
        if (to == null) return yaml;
        java.util.Set<String> known = new java.util.LinkedHashSet<>();
        if (props.baseDomain() != null && !props.baseDomain().isBlank()) known.add(props.baseDomain().trim());
        clusters.clusters().forEach(c -> { if (c.baseDomain() != null && !c.baseDomain().isBlank()) known.add(c.baseDomain().trim()); });
        String out = yaml;
        for (String from : known) {
            if (from.equals(to)) continue;
            out = replaceHost(out, "host", hostOn(p, env, from), hostOn(p, env, to));
            if (RolloutService.ENV.equals(env)) out = replaceHost(out, "previewHost", p.name + "-preview." + from, p.name + "-preview." + to);
        }
        return out;
    }

    static String replaceHost(String yaml, String key, String from, String to) {
        return Pattern.compile("(?m)^(\\s+" + key + ":\\s*[\"']?)" + Pattern.quote(from) + "([\"']?\\s*)$")
                .matcher(yaml).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + to + m.group(2)));
    }

    private String initialValues(Project p, String env) {
        String domain = clusters.baseDomainFor(env);
        boolean ingress = domain != null;
        String host = ingress ? hostOn(p, env, domain) : null;
        String base = RuntimeConfig.apply("""
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
                """.formatted(p.imageRepo, PLACEHOLDER_TAG, PULL_SECRET, ingress, ingress ? host : "example.local"), p);
        base = withDefaultTls(base, p, env);
        return RolloutService.ENV.equals(env) && rollouts.installed() ? withBlueGreen(base, previewHost(p)) : base;
    }
}
