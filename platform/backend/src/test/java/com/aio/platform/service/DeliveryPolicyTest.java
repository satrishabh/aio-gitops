package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeliveryPolicyTest {
    private static final String EXTERNAL = """
            apiVersion: aio.platform/v1alpha1
            kind: ProjectDelivery
            spec:
              registry:
                mode: external
                host: ghcr.io
                repository: example/app
                credentials:
                  pushConnection: image-push
                  pullConnection: image-pull
              artifacts:
                requestedTier: golden
            """;
    @Test void validRegistryIsIntentNotTrust() {
        var config = ProjectDeliveryConfig.parse(EXTERNAL);
        assertEquals("ghcr.io", config.host());
        assertEquals("golden", config.requestedTier());
        assertEquals(64, config.hash().length());
        var now = Instant.now();
        var unsigned = new ArtifactTrustPolicy.Evidence("sha256:" + "a".repeat(64), true, true, false, false,
                true, true, true, true, now.plusSeconds(60));
        assertEquals(ArtifactTrustPolicy.Tier.NORMAL, ArtifactTrustPolicy.evaluate(unsigned, now));
        assertFalse(ArtifactTrustPolicy.canDeploy(ArtifactTrustPolicy.Tier.NORMAL, "prod", true));
    }
    @Test void rejectsSecretInjectionDuplicatesAndUnsafeDestinations() {
        for (String yaml : new String[] {
                EXTERNAL.replace("host: ghcr.io", "host: https://ghcr.io"),
                EXTERNAL.replace("host: ghcr.io", "host: 169.254.169.254"),
                EXTERNAL.replace("host: ghcr.io", "host: ghcr.io\n    host: other.io"),
                EXTERNAL.replace("repository: example/app", "repository: ../another"),
                EXTERNAL.replace("pullConnection: image-pull", "pullConnection: image-push"),
                EXTERNAL.replace("requestedTier: golden", "requestedTier: golden\n    password: secret"),
                EXTERNAL + "\nsecret: not-allowed\n",
                EXTERNAL.replace("mode: external", "mode: managed") }) {
            assertThrows(IllegalArgumentException.class, () -> ProjectDeliveryConfig.parse(yaml));
        }
    }
    @Test void goldenRequiresEveryVerifiedPropertyAndFreshEvidence() {
        var now = Instant.now();
        var evidence = new ArtifactTrustPolicy.Evidence("sha256:" + "b".repeat(64), true, true, true, true,
                true, true, true, true, now.plusSeconds(30));
        assertEquals(ArtifactTrustPolicy.Tier.GOLDEN, ArtifactTrustPolicy.evaluate(evidence, now));
        assertEquals(ArtifactTrustPolicy.Tier.NORMAL, ArtifactTrustPolicy.evaluate(evidence, now.plusSeconds(31)));
        assertFalse(ArtifactTrustPolicy.canDeploy(ArtifactTrustPolicy.Tier.GOLDEN, "prod", false));
    }
}
