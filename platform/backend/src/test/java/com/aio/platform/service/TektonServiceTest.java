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

    @Test
    void describeSurfacesTheRealReason() {
        String generic = TektonService.describe(new KubernetesClientException("An error has occurred.",
                new ConnectException("Connection refused")));
        assertTrue(generic.contains("ConnectException: Connection refused"), generic);

        String webhook = TektonService.describe(http(500, "failed calling webhook \"webhook.pipeline.tekton.dev\""));
        assertTrue(webhook.startsWith("HTTP 500: failed calling webhook"), webhook);
    }
}
