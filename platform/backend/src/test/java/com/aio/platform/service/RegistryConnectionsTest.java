package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

class RegistryConnectionsTest {
    @Test void anotherProjectsSecretCannotBeUsedEvenIfNameMatches() {
        var k8s = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.tekton().namespace()).thenReturn("ci");
        var secret = new SecretBuilder().withNewMetadata().withName("aio-p1-image-push")
                .addToLabels("aio.platform/project-id", "2").endMetadata().addToData("config.json", "redacted").build();
        secretResponse(k8s, secret);
        var connections = new RegistryConnections(k8s, props, new ObjectMapper(), "ghcr.io");
        Project p = new Project(); p.id = 1L;
        assertThrows(SecurityException.class, () -> connections.require(p, "image-push", "ghcr.io", "team/app", "push"));
    }
    @Test void connectionMustMatchDestinationAndAccessType() {
        var k8s = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.tekton().namespace()).thenReturn("ci");
        var secret = new SecretBuilder().withNewMetadata().withName("aio-p1-image-pull")
                .addToLabels("aio.platform/project-id", "1")
                .addToAnnotations("aio.platform/registry-host", "ghcr.io")
                .addToAnnotations("aio.platform/registry-repository", "team/app")
                .addToAnnotations("aio.platform/registry-access", "pull")
                .endMetadata().addToData("config.json", "redacted").build();
        secretResponse(k8s, secret);
        var connections = new RegistryConnections(k8s, props, new ObjectMapper(), "ghcr.io");
        Project p = new Project(); p.id = 1L;
        assertSame(secret, connections.require(p, "image-pull", "ghcr.io", "team/app", "pull"));
        assertThrows(IllegalArgumentException.class, () -> connections.require(p, "image-pull", "ghcr.io", "other/app", "pull"));
        assertThrows(IllegalArgumentException.class, () -> connections.require(p, "image-pull", "ghcr.io", "team/app", "push"));
        assertThrows(IllegalArgumentException.class, () -> connections.require(p, "image-pull", "unapproved.example", "team/app", "pull"));
    }
    @Test void passwordsCannotAppearInSerializedResponsesOrDiagnostics() throws Exception {
        var registration = new RegistryConnections.Registration("image-push", "ghcr.io", "team/app", "push", "user", "sensitive-token");
        assertFalse(registration.toString().contains("sensitive-token"));
        var json = new ObjectMapper();
        assertFalse(json.writeValueAsString(registration).contains("sensitive-token"));
        assertEquals("input-token", json.readValue("{\"token\":\"input-token\"}", RegistryConnections.Registration.class).token());
    }
    @SuppressWarnings("unchecked")
    private void secretResponse(KubernetesClient k8s, io.fabric8.kubernetes.api.model.Secret secret) {
        io.fabric8.kubernetes.client.dsl.MixedOperation<io.fabric8.kubernetes.api.model.Secret,
                io.fabric8.kubernetes.api.model.SecretList, io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.Secret>> operations = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<io.fabric8.kubernetes.api.model.Secret,
                io.fabric8.kubernetes.api.model.SecretList, io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.Secret>> namespace = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.Secret> resource = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(k8s.secrets()).thenReturn(operations);
        when(operations.inNamespace("ci")).thenReturn(namespace);
        when(namespace.withName(anyString())).thenReturn(resource);
        when(resource.get()).thenReturn(secret);
    }
}
