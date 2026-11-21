package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The 2026-11-21 incident: user-profile-manager dev -> staging (values at 667b2c4), staging crash-looped. */
class PromotionCheckTest {
    static final String DEV = fixture("upm-values-dev.yaml");
    static final String STAGING = fixture("upm-values-staging.yaml");

    static String fixture(String name) {
        try (InputStream in = PromotionCheckTest.class.getResourceAsStream("/promotion/" + name)) {
            // fixtures check out with CRLF on Windows (core.autocrlf); the text-block edits below are LF
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void findsWhatStagingLackedOnTheIncidentDay() {
        var diff = PromotionCheck.compare(DEV, STAGING, "dev", "staging");

        List<String> env = diff.missing().stream().filter(f -> f.kind().equals("env")).map(PromotionCheck.Finding::name).toList();
        assertEquals(List.of("KC_DB_SCHEMA", "KC_DB_POOL_INITIAL_SIZE", "KC_DB_POOL_MIN_SIZE", "KC_DB_POOL_MAX_SIZE",
                "KC_HOSTNAME", "KC_CACHE", "JAVA_OPTS_APPEND"), env, "names only in dev; VERIFY_EMAIL (staging only) is not a finding");
        assertTrue(diff.missing().stream().anyMatch(f -> f.kind().equals("envFrom") && f.name().equals("secret profile-manager-runtime")));
        assertTrue(diff.missing().stream().anyMatch(f -> f.kind().equals("volume") && f.name().equals("trust")
                && f.detail().equals("ConfigMap rehearsal-db-trust")));
        assertTrue(diff.missing().stream().anyMatch(f -> f.kind().equals("volumeMount") && f.name().equals("/etc/aio-db")));
        assertTrue(diff.missing().stream().allMatch(PromotionCheck.Finding::copyable));

        List<String> differs = diff.differs().stream().map(PromotionCheck.Difference::key).toList();
        assertEquals(List.of("command", "args", "probes.liveness.path", "probes.readiness.path", "podSecurityContext"), differs);
        var command = diff.differs().get(0);
        assertEquals("[/opt/keycloak/bin/kc.sh]", command.from());
        assertEquals("(not set)", command.to());
        // containerPort 8080 in dev equals the chart default staging gets: not a difference
        assertFalse(differs.contains("containerPort"));

        assertEquals(Set.of(new PromotionCheck.ObjectRef("secret", "profile-manager-runtime"),
                new PromotionCheck.ObjectRef("configMap", "rehearsal-db-trust"),
                new PromotionCheck.ObjectRef("secret", "ghcr-pull")), diff.sourceRefs());
    }

    @Test
    void missingObjectsAreNamedWithKubectlCommandsAndNeverCopyable() {
        var diff = PromotionCheck.compare(DEV, STAGING, "dev", "staging");
        // what the staging namespace had: only the pull secret
        var found = PromotionCheck.missingObjects(diff.sourceRefs(), r -> r.name().equals("ghcr-pull"), "dev", "staging", "staging", "dev");
        assertEquals(List.of("profile-manager-runtime", "rehearsal-db-trust"), found.stream().map(PromotionCheck.Finding::name).toList());
        var secret = found.get(0);
        assertEquals("secret", secret.kind());
        assertFalse(secret.copyable());
        assertEquals("kubectl -n staging create secret generic profile-manager-runtime --from-literal=KEY=value", secret.commands().get(0));
        assertTrue(secret.commands().get(1).startsWith("kubectl -n dev get secret profile-manager-runtime -o json"));
        assertTrue(secret.commands().get(1).endsWith("kubectl -n staging apply -f -"));
        assertEquals("kubectl -n staging create configmap rehearsal-db-trust --from-file=<file>", found.get(1).commands().get(0));
        // unknown (cluster not reachable) is not reported as missing
        assertTrue(PromotionCheck.missingObjects(diff.sourceRefs(), r -> null, "dev", "staging", "staging", "dev").isEmpty());
    }

    @Test
    void identicalFilesAreOk() {
        var diff = PromotionCheck.compare(DEV, DEV, "dev", "staging");
        assertTrue(diff.missing().isEmpty());
        assertTrue(diff.differs().isEmpty());
    }

    @Test
    void optionalReferencesAreNotRequired() {
        String yaml = """
                envFrom:
                  - secretRef: {name: maybe, optional: true}
                  - configMapRef: {name: needed}
                env:
                  - name: A
                    valueFrom: {secretKeyRef: {name: db, key: url}}
                extraVolumes:
                  - name: certs
                    secret: {secretName: tls-certs}
                """;
        var refs = PromotionCheck.refs(PromotionCheck.load(yaml));
        assertEquals(Set.of(new PromotionCheck.ObjectRef("configMap", "needed"), new PromotionCheck.ObjectRef("secret", "db"),
                new PromotionCheck.ObjectRef("secret", "tls-certs")), refs);
    }

    // ------------------------------------------------------------------ copy settings

    @Test
    void copySettingsBringsDevRuntimeSettingsAndKeepsStagingSpecifics() {
        String merged = PromotionCheck.merge(DEV, STAGING);
        Map<String, Object> m = PromotionCheck.load(merged);
        Map<String, Object> before = PromotionCheck.load(STAGING);

        // copied
        assertEquals(List.of("/opt/keycloak/bin/kc.sh"), m.get("command"));
        assertEquals(List.of("start", "--optimized"), m.get("args"));
        assertEquals(8080, m.get("containerPort"));
        assertEquals("/health/live", J.str(m.get("probes"), "liveness", "path"));
        assertEquals(1000, J.get(m.get("podSecurityContext"), "fsGroup"));
        assertEquals(List.of(Map.of("secretRef", Map.of("name", "profile-manager-runtime"))), m.get("envFrom"));
        assertEquals("rehearsal-db-trust", J.str(PromotionCheck.maps(m.get("extraVolumes")).get(0), "configMap", "name"));
        assertEquals("/etc/aio-db", PromotionCheck.maps(m.get("extraVolumeMounts")).get(0).get("mountPath"));

        // never copied: staging keeps its own
        for (String key : List.of("image", "ingress", "replicaCount", "resources", "spot", "imagePullSecrets", "pdb"))
            assertEquals(before.get(key), m.get(key), key);
        assertFalse(m.containsKey("routes"), "dev's custom domain must not move to staging");
        assertTrue(merged.contains("host: user-profile-manager-staging.34.180.3.18.nip.io"));
        assertTrue(merged.contains("tag: \"0.0.0\""), "the tag is set by the promotion, not by the merge");

        // env: staging's entries first and unchanged, dev's missing names appended
        var env = PromotionCheck.maps(m.get("env"));
        assertEquals(List.of("KC_DB", "KC_HTTP_ENABLED", "KC_PROXY_HEADERS", "VERIFY_EMAIL", "KC_DB_SCHEMA",
                "KC_DB_POOL_INITIAL_SIZE", "KC_DB_POOL_MIN_SIZE", "KC_DB_POOL_MAX_SIZE", "KC_HOSTNAME", "KC_CACHE",
                "JAVA_OPTS_APPEND"), env.stream().map(e -> e.get("name")).toList());
        assertEquals("1", env.get(5).get("value"), "string values stay strings");
        assertEquals("-XX:MaxRAMPercentage=65 -XX:InitialRAMPercentage=20", env.get(10).get("value"));

        // after copying, the check only reports what git can't carry
        var after = PromotionCheck.compare(DEV, merged, "dev", "staging");
        assertTrue(after.missing().isEmpty(), after.missing().toString());
        assertTrue(after.differs().isEmpty(), after.differs().toString());
        assertDoesNotThrow(() -> GitOpsService.validateValues(GitOpsService.replaceTag(merged, "d5cd7e4d7180"),
                "ghcr.io/satrishabh/user-profile-manager", "staging"));
    }

    @Test
    void mergeHandlesCrlfValuesFiles() {
        String merged = PromotionCheck.merge(DEV.replace("\n", "\r\n"), STAGING.replace("\n", "\r\n"));
        var env = PromotionCheck.maps(PromotionCheck.load(merged).get("env"));
        assertTrue(env.stream().anyMatch(e -> "KC_DB_SCHEMA".equals(e.get("name"))), merged);
        assertFalse(merged.contains("\r"), "merged file is written with LF");
    }

    @Test
    void copySettingsNeverOverwritesTargetEnvValues() {
        String target = STAGING.replace("""
                  - name: KC_HTTP_ENABLED
                    value: "true"
                """, """
                  - name: KC_HTTP_ENABLED
                    value: "false"
                  - name: KC_HOSTNAME
                    value: "https://staging.example.com"
                """);
        var env = PromotionCheck.maps(PromotionCheck.load(PromotionCheck.merge(DEV, target)).get("env"));
        Map<Object, Object> byName = new java.util.HashMap<>();
        env.forEach(e -> byName.put(e.get("name"), e.get("value")));
        assertEquals("false", byName.get("KC_HTTP_ENABLED"));
        assertEquals("https://staging.example.com", byName.get("KC_HOSTNAME"));
        assertEquals(1, env.stream().filter(e -> e.get("name").equals("KC_HOSTNAME")).count());
    }

    @Test
    void listsAreUnionedAndFlowStyleTargetsRewritten() {
        String source = """
                envFrom:
                  - secretRef: {name: a}
                  - configMapRef: {name: b}
                extraVolumes:
                  - name: v1
                    emptyDir: {}
                env:
                  - name: X
                    valueFrom: {secretKeyRef: {name: s, key: k}}
                """;
        String target = """
                image:
                  tag: "1"
                env: []
                envFrom:
                  - secretRef: {name: own}
                  - secretRef: {name: a}
                extraVolumes: [{name: v1, configMap: {name: keep-mine}}]
                """;
        String merged = PromotionCheck.merge(source, target);
        Map<String, Object> m = PromotionCheck.load(merged);
        assertEquals(List.of(Map.of("secretRef", Map.of("name", "own")), Map.of("secretRef", Map.of("name", "a")),
                Map.of("configMapRef", Map.of("name", "b"))), m.get("envFrom"));
        assertEquals(List.of(Map.of("name", "v1", "configMap", Map.of("name", "keep-mine"))), m.get("extraVolumes"),
                "a volume of the same name in the target wins");
        assertEquals(List.of(Map.of("name", "X", "valueFrom", Map.of("secretKeyRef", Map.of("name", "s", "key", "k")))), m.get("env"));
        assertEquals(target, PromotionCheck.merge("image:\n  tag: \"2\"\n", target), "nothing to copy: unchanged");
    }

    @Test
    void envFindingsCarryTheSourceValueAndFlagEnvSpecificOnes() {
        var env = PromotionCheck.compare(DEV, STAGING, "dev", "staging").missing().stream()
                .filter(f -> f.kind().equals("env")).collect(java.util.stream.Collectors.toMap(PromotionCheck.Finding::name, f -> f));
        assertEquals("https://profile.sarvaikam.com", env.get("KC_HOSTNAME").value());
        assertTrue(env.get("KC_HOSTNAME").envSpecific(), "a URL is likely different in staging");
        assertEquals("profile_manager", env.get("KC_DB_SCHEMA").value());
        assertFalse(env.get("KC_DB_SCHEMA").envSpecific());
        assertFalse(env.get("KC_CACHE").envSpecific());
        assertFalse(env.get("JAVA_OPTS_APPEND").envSpecific());
        assertNull(env.get("KC_CACHE").valueFrom());

        assertTrue(PromotionCheck.envSpecific("jdbc:postgresql://db/app_dev", "dev"));
        assertTrue(PromotionCheck.envSpecific("my-app-dev", "dev"));
        assertTrue(PromotionCheck.envSpecific("staging", "dev"));
        assertTrue(PromotionCheck.envSpecific("api.example.com", "dev"));
        assertFalse(PromotionCheck.envSpecific("device", "dev"));
        assertFalse(PromotionCheck.envSpecific("5", "dev"));

        String source = """
                env:
                  - name: DB_URL
                    valueFrom: {secretKeyRef: {name: db, key: url}}
                """;
        var ref = PromotionCheck.compare(source, "env: []\n", "dev", "staging").missing().get(0);
        assertNull(ref.value(), "values behind a reference are never shown");
        assertEquals("secretKeyRef db/url", ref.valueFrom());
    }

    @Test
    void envOverridesReplaceSourceValuesOfAddedVarsOnly() {
        String merged = PromotionCheck.merge(DEV, STAGING, Map.of("KC_HOSTNAME", "https://profile-staging.example.com"));
        var env = PromotionCheck.maps(PromotionCheck.load(merged).get("env"));
        Map<Object, Object> byName = new java.util.HashMap<>();
        env.forEach(e -> byName.put(e.get("name"), e.get("value")));
        assertEquals("https://profile-staging.example.com", byName.get("KC_HOSTNAME"));
        assertEquals("profile_manager", byName.get("KC_DB_SCHEMA"), "not overridden: dev's value");
        assertEquals("postgres", byName.get("KC_DB"), "target value untouched");
        assertFalse(merged.contains("profile.sarvaikam.com"));

        // the target has no env block at all: overrides still apply
        String noEnv = STAGING.replaceAll("(?s)env:\n(  .*?\n)+(?=resources:)", "");
        assertFalse(noEnv.contains("env:"));
        String merged2 = PromotionCheck.merge(DEV, noEnv, Map.of("KC_HOSTNAME", "x"));
        assertTrue(merged2.contains("value: \"x\""));
        assertEquals(10, PromotionCheck.maps(PromotionCheck.load(merged2).get("env")).size());

        // a var the target already has, one not in the source, or one behind a reference can't be set this way
        assertThrows(IllegalArgumentException.class, () -> PromotionCheck.merge(DEV, STAGING, Map.of("KC_DB", "mysql")));
        assertThrows(IllegalArgumentException.class, () -> PromotionCheck.merge(DEV, STAGING, Map.of("NOPE", "1")));
        String refSource = """
                env:
                  - name: DB_URL
                    valueFrom: {secretKeyRef: {name: db, key: url}}
                """;
        assertThrows(IllegalArgumentException.class, () -> PromotionCheck.merge(refSource, "env: []\n", Map.of("DB_URL", "leak")));
    }

    @Test
    void neverCopiedKeysStayPutEvenWhenSourceHasThem() {
        String source = """
                image:
                  tag: "new"
                replicaCount: 3
                resources:
                  limits: {cpu: "4"}
                spot:
                  enabled: true
                services:
                  - name: db
                ingress:
                  host: a.example
                routes:
                  - host: x.example
                """;
        String target = "image:\n  tag: \"old\"\nreplicaCount: 1\ningress:\n  host: b.example\n";
        assertEquals(target, PromotionCheck.merge(source, target));
    }
}
