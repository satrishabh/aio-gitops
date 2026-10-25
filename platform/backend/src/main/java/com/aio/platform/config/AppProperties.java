package com.aio.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String publicUrl,
        String registry,
        String registryUser,
        String baseDomain,
        Tekton tekton,
        GitOps gitops,
        ArgoCd argocd,
        GitHub github,
        Security security,
        Cleanup cleanup) {

    /** PipelineRun pruning: keep at least {@code keepPerProject} newest runs, delete older than {@code maxAgeHours}. */
    public record Cleanup(int keepPerProject, int maxAgeHours) {}

    public record Tekton(String namespace, String pipeline, String serviceAccount, String storageSize,
                         String registrySecret, String githubTokenSecret, String timeout) {}

    public record GitOps(String repoUrl, String owner, String repo, String branch, String appsPath, String chartPath) {}

    public record ArgoCd(String url, String token, String project, String destinationServer, boolean insecure) {}

    public record GitHub(String apiUrl, String token) {}

    public record Security(List<String> allowedUsers, List<String> allowedOrgs, List<String> approvers) {
        public Security {
            allowedUsers = clean(allowedUsers);
            allowedOrgs = clean(allowedOrgs);
            approvers = clean(approvers);
        }

        private static List<String> clean(List<String> in) {
            if (in == null) return List.of();
            return in.stream().map(String::trim).filter(s -> !s.isEmpty()).map(String::toLowerCase).toList();
        }
    }
}
