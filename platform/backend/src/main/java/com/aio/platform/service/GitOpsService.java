package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Owns the per-project Helm values files in the GitOps repo:
 * {@code <apps-path>/<project>/values-<env>.yaml}. Argo CD watches these files;
 * changing {@code image.tag} is how every deploy, promotion and rollback happens.
 */
@Service
public class GitOpsService {
    public static final List<String> ENVS = List.of("dev", "staging", "prod");

    private static final Pattern TAG_LINE = Pattern.compile("(?m)^(\\s+tag:\\s*).*$");
    private static final Pattern TAG_VALUE = Pattern.compile("(?m)^\\s+tag:\\s*[\"']?([^\"'\\s#]+)[\"']?");

    private final GitHubClient github;
    private final AppProperties props;

    public GitOpsService(GitHubClient github, AppProperties props) {
        this.github = github;
        this.props = props;
    }

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

    public String readTag(Project p, String env) {
        var g = props.gitops();
        var file = github.getFile(g.owner(), g.repo(), valuesPath(g, p.name, env), g.branch());
        if (file == null) throw new IllegalStateException("values file missing for " + p.name + "/" + env);
        return extractTag(file.content());
    }

    public void setTag(Project p, String env, String tag, String message) {
        var g = props.gitops();
        String path = valuesPath(g, p.name, env);
        var file = github.getFile(g.owner(), g.repo(), path, g.branch());
        if (file == null) throw new IllegalStateException("values file missing: " + path);
        String updated = replaceTag(file.content(), tag);
        if (updated.equals(file.content())) return;
        github.putFile(g.owner(), g.repo(), path, g.branch(), updated, file.sha(), message);
    }

    static String extractTag(String yaml) {
        Matcher m = TAG_VALUE.matcher(yaml);
        return m.find() ? m.group(1) : null;
    }

    static String replaceTag(String yaml, String tag) {
        if (!tag.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("invalid image tag: " + tag);
        Matcher m = TAG_LINE.matcher(yaml);
        if (!m.find()) throw new IllegalStateException("no image.tag line in values file");
        return yaml.substring(0, m.start()) + m.group(1) + "\"" + tag + "\"" + yaml.substring(m.end());
    }

    private String initialValues(Project p, String env) {
        int replicas = switch (env) { case "prod" -> 3; case "staging" -> 2; default -> 1; };
        String domain = props.baseDomain();
        boolean ingress = domain != null && !domain.isBlank();
        String host = "prod".equals(env) ? p.name + "." + domain : p.name + "-" + env + "." + domain;
        return """
                # Managed by AIO platform - image.tag is updated by CI / promotions
                image:
                  repository: %s
                  tag: "0.0.0"
                replicaCount: %d
                pdb:
                  enabled: %s
                ingress:
                  enabled: %s
                  host: %s
                """.formatted(p.imageRepo, replicas, !"dev".equals(env), ingress, ingress ? host : "example.local");
    }
}
