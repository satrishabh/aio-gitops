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
        Cleanup cleanup,
        List<Cluster> clusters) {

    /**
     * A workload cluster registered in Argo CD. {@code server} must match the Argo CD cluster's server URL;
     * {@code envs} lists the environments it hosts (each env maps to exactly one cluster);
     * {@code baseDomain} is that cluster's ingress domain (app hosts are {@code <project>-<env>.<baseDomain>});
     * {@code rollouts}: Argo Rollouts is installed there (enables blue-green on prod).
     *
     * <p>Domains / certificates: {@code ingressIp} is where custom domains must point (A record);
     * {@code issuer} the cert-manager ClusterIssuer (default {@code gts}: Google Trust Services, HTTP-01, plus
     * DNS-01 for the managed zone); {@code dnsZone} a domain suffix whose DNS is in Cloud DNS (external-dns creates
     * the records); {@code tls} turns on HTTPS for the generated default hosts.
     */
    public record Cluster(String name, String server, List<String> envs, String baseDomain, Boolean rollouts,
                          String ingressIp, String issuer, String dnsZone, Boolean tls) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Cluster {}   // the canonical constructor is the one bound from config

        public Cluster(String name, String server, List<String> envs, String baseDomain, Boolean rollouts) {
            this(name, server, envs, baseDomain, rollouts, null, null, null, null);
        }
    }

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
