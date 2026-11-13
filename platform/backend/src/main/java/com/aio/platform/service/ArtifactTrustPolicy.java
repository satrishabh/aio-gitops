package com.aio.platform.service;

import java.time.Instant;
import java.util.Set;

/** Pure evaluation of verified evidence, independent of registry ownership and user-supplied labels. */
public final class ArtifactTrustPolicy {
    private ArtifactTrustPolicy() {}
    public enum Tier { NORMAL, VERIFIED, GOLDEN }
    public record Evidence(String digest, boolean testsPassed, boolean scanPassed, boolean signatureVerified,
                           boolean provenanceVerified, boolean sbomPresent, boolean approvedBuilder,
                           boolean approvedBase, boolean goldenApproved, Instant expiresAt) {}
    public static Tier evaluate(Evidence e, Instant now) {
        if (e == null || e.digest() == null || !e.digest().matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Valid image digest is required");
        if (e.expiresAt() == null || !e.expiresAt().isAfter(now)) return Tier.NORMAL;
        if (!(e.testsPassed() && e.scanPassed() && e.signatureVerified() && e.provenanceVerified()
                && e.sbomPresent() && e.approvedBuilder())) return Tier.NORMAL;
        return e.approvedBase() && e.goldenApproved() ? Tier.GOLDEN : Tier.VERIFIED;
    }
    public static boolean canDeploy(Tier tier, String environment, boolean scanPassed) {
        if (tier == null) return false;
        if (!Set.of("dev", "staging", "prod").contains(environment)) throw new IllegalArgumentException("Unknown environment");
        return scanPassed && switch (environment) {
            case "dev" -> true;
            case "staging" -> tier != Tier.NORMAL;
            case "prod" -> tier == Tier.GOLDEN;
            default -> false;
        };
    }
}
