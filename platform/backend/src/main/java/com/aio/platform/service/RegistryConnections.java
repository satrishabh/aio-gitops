package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Connections are project-scoped Secrets. The API never returns their contents. */
@Service
public class RegistryConnections {
    private final KubernetesClient k8s;
    private final AppProperties props;
    private final ObjectMapper json;
    private final java.util.Set<String> allowedHosts;
    public RegistryConnections(KubernetesClient k8s, AppProperties props, ObjectMapper json,
            @Value("${app.delivery.registry-hosts:ghcr.io,index.docker.io,docker.io}") String allowedHosts) {
        this.k8s = k8s; this.props = props; this.json = json;
        this.allowedHosts = java.util.Arrays.stream(allowedHosts.split(",")).map(String::trim).collect(java.util.stream.Collectors.toSet());
    }
    public record Registration(String name, String host, String repository, String access, String username,
                               @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String token) {
        @Override public String toString() { return "Registry registration [credentials redacted]"; }
    }
    public record Connection(String name, String host, String repository, String access, String status) {}
    public Connection register(Project project, Registration registration) {
        String name = registration.name();
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,31}")) throw new IllegalArgumentException("Invalid connection name (maximum 32 characters)");
        if (!allowedHosts.contains(registration.host())) throw new IllegalArgumentException("Registry host is not approved by the platform operator");
        if (registration.repository() == null || registration.repository().length() > 200 || !registration.repository().matches("[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*"))
            throw new IllegalArgumentException("Invalid repository");
        if (!java.util.Set.of("push", "pull").contains(java.util.Objects.toString(registration.access(), "")))
            throw new IllegalArgumentException("Connection access must be push or pull");
        if (registration.username() == null || registration.username().length() > 200 || registration.username().isBlank() || registration.username().contains(":"))
            throw new IllegalArgumentException("Registry username is required and cannot contain a colon");
        if (registration.token() == null || registration.token().isBlank() || registration.token().length() > 16_384)
            throw new IllegalArgumentException("Registry token is required (maximum 16384 characters)");
        String secretName = secretName(project, name);
        var current = k8s.secrets().inNamespace(props.tekton().namespace()).withName(secretName).get();
        if (current != null) requireProject(current, project);
        try {
            String auth = b64(registration.username() + ":" + registration.token());
            String authHost = java.util.Set.of("docker.io", "index.docker.io").contains(registration.host()) ? "https://index.docker.io/v1/" : registration.host();
            String docker = json.writeValueAsString(Map.of("auths", Map.of(authHost, Map.of("auth", auth))));
            Secret secret = new SecretBuilder().withNewMetadata().withName(secretName).withNamespace(props.tekton().namespace())
                    .addToLabels("aio.platform/project-id", project.id.toString())
                    .addToAnnotations("aio.platform/registry-host", registration.host())
                    .addToAnnotations("aio.platform/registry-repository", registration.repository())
                    .addToAnnotations("aio.platform/registry-access", registration.access())
                    .addToAnnotations("aio.platform/connection-name", name).endMetadata()
                    .withType("Opaque").addToData("config.json", b64(docker)).build();
            k8s.secrets().inNamespace(props.tekton().namespace()).resource(secret).serverSideApply();
            return new Connection(name, registration.host(), registration.repository(), registration.access(), "REGISTERED");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot encode registry connection"); }
    }
    public Secret require(Project project, String connection, String host, String repository, String access) {
        if (!allowedHosts.contains(host)) throw new IllegalArgumentException("Registry host is no longer approved by the platform operator");
        Secret secret = k8s.secrets().inNamespace(props.tekton().namespace()).withName(secretName(project, connection)).get();
        if (secret == null) throw new IllegalArgumentException("Registry connection is not registered: " + connection);
        requireProject(secret, project);
        var annotations = secret.getMetadata().getAnnotations();
        if (annotations == null || !host.equals(annotations.get("aio.platform/registry-host"))
                || !repository.equals(annotations.get("aio.platform/registry-repository"))
                || !access.equals(annotations.get("aio.platform/registry-access")))
            throw new IllegalArgumentException("Registry connection scope does not match configuration");
        if (secret.getData() == null || !secret.getData().containsKey("config.json")) throw new IllegalArgumentException("Invalid registry connection");
        return secret;
    }
    public void revoke(Project project, String connection) {
        String name = secretName(project, connection);
        Secret existing = k8s.secrets().inNamespace(props.tekton().namespace()).withName(name).get();
        if (existing != null) { requireProject(existing, project); k8s.secrets().inNamespace(props.tekton().namespace()).withName(name).delete(); }
    }
    public void deleteProject(Project project) {
        for (Secret secret : k8s.secrets().inNamespace(props.tekton().namespace())
                .withLabel("aio.platform/project-id", project.id.toString()).list().getItems()) {
            String name = secret.getMetadata().getName();
            if (name.startsWith("aio-p" + project.id + "-")) {
                requireProject(secret, project);
                k8s.secrets().inNamespace(props.tekton().namespace()).withName(name).delete();
            }
        }
    }
    public void copyForRun(Project project, String connection, String host, String repository, String access, Long runId) {
        Secret source = require(project, connection, host, repository, access);
        String name = runSecret(runId, access);
        Secret secret = new SecretBuilder().withNewMetadata().withName(name).withNamespace(props.tekton().namespace())
                .addToLabels("aio.platform/project-id", project.id.toString()).addToLabels("aio.platform/run-id", runId.toString())
                .endMetadata().withType("Opaque").withData(source.getData()).build();
        k8s.secrets().inNamespace(props.tekton().namespace()).resource(secret).create();
    }
    public static String runSecret(Long runId, String access) { return "aio-run-" + runId + "-" + access; }
    private static String secretName(Project project, String connection) {
        if (connection == null || !connection.matches("[a-z][a-z0-9-]{0,31}")) throw new IllegalArgumentException("Invalid registry connection name");
        return "aio-p" + project.id + "-" + connection;
    }
    private static void requireProject(Secret secret, Project project) {
        if (secret.getMetadata().getLabels() == null || !project.id.toString().equals(secret.getMetadata().getLabels().get("aio.platform/project-id")))
            throw new SecurityException("Registry connection does not belong to this project");
    }
    private static String b64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
}
