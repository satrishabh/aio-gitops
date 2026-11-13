package com.aio.platform.service;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkloadPullCredentialsTest {
    static String encoded(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
    @Test void acceptsIndependentRegistryCredentialWithoutSourceToken() {
        for (String host : new String[]{"account.dkr.ecr.region.amazonaws.com", "team.azurecr.io", "registry.cn-hangzhou.aliyuncs.com"}) {
            String data = encoded("{\"auths\":{\"" + host + "\":{\"auth\":\"dTpw\"}}}");
            var secret = new SecretBuilder().withType("kubernetes.io/dockerconfigjson").addToData(".dockerconfigjson", data).build();
            assertEquals(data, WorkloadPullCredentials.fromSecret(secret, host));
        }
    }
    @Test void rejectsWrongRegistryAndSanitizesMalformedPayload() {
        for (String value : new String[]{"sensitive-bad-base64", encoded("not-json-sensitive"), encoded("{\"auths\":{\"other.example\":{\"auth\":\"sensitive\"}}}")}) {
            var secret = new SecretBuilder().withType("kubernetes.io/dockerconfigjson").addToData(".dockerconfigjson", value).build();
            var error = assertThrows(IllegalStateException.class, () -> WorkloadPullCredentials.fromSecret(secret, "team.azurecr.io"));
            assertFalse(error.getMessage().contains("sensitive"));
            assertNull(error.getCause());
        }
    }
    @Test void acceptsDockerHubCanonicalKey() {
        String data = encoded("{\"auths\":{\"https://index.docker.io/v1/\":{\"auth\":\"dTpw\"}}}");
        var secret = new SecretBuilder().withType("kubernetes.io/dockerconfigjson").addToData(".dockerconfigjson", data).build();
        assertEquals(data, WorkloadPullCredentials.fromSecret(secret, "docker.io"));
    }
    @Test void rejectsMissingOrWrongSecretType() {
        assertThrows(IllegalStateException.class, () -> WorkloadPullCredentials.fromSecret(null, "ghcr.io"));
        assertThrows(IllegalStateException.class, () -> WorkloadPullCredentials.fromSecret(new SecretBuilder().withType("Opaque").build(), "ghcr.io"));
    }
    @Test void copiesOnlyTheConfiguredRegistry() {
        String data = encoded("{\"auths\":{\"wanted.example\":{\"auth\":\"dTpw\"},\"unrelated.example\":{\"auth\":\"private\"}}}");
        var secret = new SecretBuilder().withType("kubernetes.io/dockerconfigjson").addToData(".dockerconfigjson", data).build();
        String result = new String(Base64.getDecoder().decode(WorkloadPullCredentials.fromSecret(secret, "wanted.example")), StandardCharsets.UTF_8);
        assertTrue(result.contains("wanted.example"));
        assertFalse(result.contains("unrelated.example"));
        assertFalse(result.contains("private"));
    }
    @Test void neverReusesGithubTokenForOtherRegistries() {
        assertThrows(IllegalStateException.class, () -> WorkloadPullCredentials.legacyConfig("team.azurecr.io", "user", "github-secret"));
        assertNotNull(WorkloadPullCredentials.legacyConfig("ghcr.io", "user", "github-secret"));
        assertNull(WorkloadPullCredentials.legacyConfig("ghcr.io", "user", ""));
    }
}
