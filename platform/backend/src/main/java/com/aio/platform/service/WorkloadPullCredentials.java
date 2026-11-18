package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separates managed workload image reads from source/GitOps authorization. */
@Component
public class WorkloadPullCredentials {
    private final KubernetesClient k8s;
    private final AppProperties props;
    private final String sourceSecret;

    public WorkloadPullCredentials(KubernetesClient k8s, AppProperties props,
                                  @Value("${AIO_WORKLOAD_PULL_SECRET:}") String sourceSecret) {
        this.k8s = k8s;
        this.props = props;
        this.sourceSecret = sourceSecret == null ? "" : sourceSecret.trim();
    }

    public String encodedConfig(String host) {
        if (!sourceSecret.isEmpty()) {
            Secret source = k8s.secrets().inNamespace(props.tekton().namespace()).withName(sourceSecret).get();
            return fromSecret(source, host);
        }
        // Retain the existing GHCR installation contract without leaking its token to other registries.
        return legacyConfig(host, props.registryUser(), props.github().token());
    }

    static String legacyConfig(String host, String username, String token) {
        if (!"ghcr.io".equals(host))
            throw new IllegalStateException("Configure AIO_WORKLOAD_PULL_SECRET for this managed registry");
        if (token == null || token.isBlank()) return null;
        if (username == null || username.isBlank())
            throw new IllegalStateException("Managed registry username is required");
        return Base64.getEncoder().encodeToString(ClusterService.dockerConfig(host, username, token)
                .getBytes(StandardCharsets.UTF_8));
    }

    static String fromSecret(Secret secret, String host) {
        String invalid = "Managed workload pull Secret must contain a valid dockerconfigjson entry for the configured registry";
        if (secret == null || !"kubernetes.io/dockerconfigjson".equals(secret.getType()) || secret.getData() == null)
            throw new IllegalStateException(invalid);
        String encoded = secret.getData().get(".dockerconfigjson");
        try {
            var auths = new ObjectMapper().readTree(Base64.getDecoder().decode(encoded)).path("auths");
            var selected = new ObjectMapper().createObjectNode();
            var fields = auths.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey().replaceFirst("^https?://", "").replaceFirst("/.*$", "");
                if ("index.docker.io".equals(key) || "registry-1.docker.io".equals(key)) key = "docker.io";
                String wanted = "index.docker.io".equals(host) ? "docker.io" : host;
                var credentials = entry.getValue();
                boolean populated = !credentials.path("auth").asText("").isBlank()
                        || !credentials.path("identitytoken").asText("").isBlank()
                        || (!credentials.path("username").asText("").isBlank() && !credentials.path("password").asText("").isBlank());
                if (wanted.equals(key) && populated) selected.set(entry.getKey(), credentials);
            }
            if (selected.isEmpty()) throw new IllegalArgumentException();
            var config = new ObjectMapper().createObjectNode();
            config.set("auths", selected);
            return Base64.getEncoder().encodeToString(config.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Do not include parser errors or secret payloads in logs/API diagnostics.
            throw new IllegalStateException(invalid);
        }
    }
}
