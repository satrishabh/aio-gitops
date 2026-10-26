package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

class TektonServiceTest {

    private static KubernetesClientException http(int code, String msg) {
        return new KubernetesClientException(new StatusBuilder().withCode(code).withMessage(msg).build());
    }

    @Test
    void retriesTransientFailuresOnly() {
        assertTrue(TektonService.isRetryable(new KubernetesClientException("An error has occurred.", new ConnectException("refused"))));
        assertTrue(TektonService.isRetryable(http(503, "webhook unavailable")));
        assertTrue(TektonService.isRetryable(http(500, "Internal error occurred: failed calling webhook")));
        assertTrue(TektonService.isRetryable(http(401, "Unauthorized")));   // stale token -> fresh client
        assertTrue(TektonService.isRetryable(http(429, "Too Many Requests")));
        assertFalse(TektonService.isRetryable(http(403, "forbidden")));
        assertFalse(TektonService.isRetryable(http(404, "pipeline not found")));
        assertFalse(TektonService.isRetryable(http(400, "admission webhook denied the request")));
    }

    /** Regression: fabric8 6.13 + Jackson 2.19+ threw NPE ("keySerializer is null") on exactly this shape. */
    @Test
    void pipelineRunSerialisesWithTheBundledJackson() {
        var pr = new io.fabric8.kubernetes.api.model.GenericKubernetesResource();
        pr.setApiVersion("tekton.dev/v1");
        pr.setKind("PipelineRun");
        pr.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withGenerateName("app-ci-")
                .addToLabels("aio.platform/run-id", "1").build());
        pr.setAdditionalProperty("spec", java.util.Map.of(
                "pipelineRef", java.util.Map.of("name", "ci-pipeline"),
                "params", java.util.List.of(java.util.Map.of("name", "repo-url", "value", "https://github.com/a/b.git")),
                "workspaces", java.util.List.of(java.util.Map.of("name", "source", "volumeClaimTemplate",
                        java.util.Map.of("spec", java.util.Map.of("resources", java.util.Map.of("requests", java.util.Map.of("storage", "2Gi")))))))); 
        String json = new io.fabric8.kubernetes.client.utils.KubernetesSerialization().asJson(pr);
        assertTrue(json.contains("\"kind\":\"PipelineRun\""), json);
        assertTrue(json.contains("\"storage\":\"2Gi\""), json);
    }

    @Test
    void describeSurfacesTheRealReason() {
        String generic = TektonService.describe(new KubernetesClientException("An error has occurred.",
                new ConnectException("Connection refused")));
        assertTrue(generic.contains("ConnectException: Connection refused"), generic);

        String webhook = TektonService.describe(http(500, "failed calling webhook \"webhook.pipeline.tekton.dev\""));
        assertTrue(webhook.startsWith("HTTP 500: failed calling webhook"), webhook);
    }
}
