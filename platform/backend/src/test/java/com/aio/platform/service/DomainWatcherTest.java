package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainWatcherTest {

    @Test
    void dnsMustResolveToExpectedIpWhenKnown() {
        assertFalse(DomainWatcher.dnsResult(List.of(), "192.0.2.1").ok());
        assertFalse(DomainWatcher.dnsResult(List.of("192.0.2.2"), "192.0.2.1").ok());
        assertTrue(DomainWatcher.dnsResult(List.of("192.0.2.1"), "192.0.2.1").ok());
        assertTrue(DomainWatcher.dnsResult(List.of("192.0.2.2"), null).ok());
    }

    @Test
    void recognizesReadyAndFailedCertificates() {
        Instant expiry = Instant.parse("2027-01-01T00:00:00Z");
        Map<String, Object> ready = Map.of("status", Map.of(
                "notAfter", expiry.toString(),
                "conditions", List.of(Map.of("type", "Ready", "status", "True"))));
        Map<String, Object> failed = Map.of("status", Map.of("conditions", List.of(
                Map.of("type", "Issuing", "reason", "Failed", "message", "challenge failed"))));

        var readyResult = DomainWatcher.certResult(ready);
        assertTrue(readyResult.ready());
        assertEquals(expiry, readyResult.notAfter());

        var failedResult = DomainWatcher.certResult(failed);
        assertTrue(failedResult.failed());
        assertTrue(failedResult.message().contains("challenge failed"));
        assertFalse(DomainWatcher.certResult(null).ready());
    }

    @Test
    void buildsBoundedCertificateSecretName() {
        assertEquals("tls-example-com", DomainWatcher.secretName("example.com"));
        assertTrue(DomainWatcher.secretName("a".repeat(250) + ".example").length() <= 63);
    }
}
