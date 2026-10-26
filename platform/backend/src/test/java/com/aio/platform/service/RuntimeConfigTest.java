package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aio.platform.domain.Project;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeConfigTest {

    private static Project project(String envJson) {
        Project p = new Project();
        p.envVars = envJson;
        p.cpuRequest = "100m"; p.cpuLimit = "1"; p.memoryRequest = "256Mi"; p.memoryLimit = "1Gi";
        return p;
    }

    @Test
    void writesEnvAndResourcesAndKeepsTheRest() {
        String yaml = """
                image:
                  repository: ghcr.io/acme/shop
                  tag: "abc"
                replicaCount: 1
                ingress:
                  enabled: false
                """;
        Project p = project(RuntimeConfig.writeEnv(List.of(new RuntimeConfig.Env("GREETING", "hi: \"there\"\n#x"))));
        String out = RuntimeConfig.apply(yaml, p);
        assertTrue(out.contains("tag: \"abc\""));
        assertTrue(out.contains("replicaCount: 1"));
        assertTrue(out.contains("ingress:\n  enabled: false"));
        assertTrue(out.contains("  - name: GREETING\n    value: \"hi: \\\"there\\\"\\n#x\"\n"), out);
        assertTrue(out.contains("limits: { cpu: \"1\", memory: \"1Gi\" }"));
        // idempotent: applying twice replaces the blocks instead of duplicating them
        String again = RuntimeConfig.apply(out, p);
        assertEquals(out, again);
        assertEquals(1, again.split("(?m)^env:").length - 1);
        assertEquals(1, again.split("(?m)^resources:").length - 1);
    }

    @Test
    void replacesBlockInTheMiddleAndEmptyEnv() {
        String yaml = "env:\n  - name: OLD\n    value: \"x\"\nresources:\n  limits: { cpu: 9 }\nreplicaCount: 2\n";
        String out = RuntimeConfig.apply(yaml, project("[]"));
        assertFalse(out.contains("OLD"));
        assertTrue(out.startsWith("env: []\n"));
        assertTrue(out.endsWith("replicaCount: 2\n"), out);
    }

    @Test
    void validatesNamesAndResources() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validEnv(List.of(new RuntimeConfig.Env("1BAD", "v"))));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validEnv(List.of(
                new RuntimeConfig.Env("A", "1"), new RuntimeConfig.Env("A", "2"))));
        assertEquals(1, RuntimeConfig.validEnv(List.of(new RuntimeConfig.Env("  ", "ignored"), new RuntimeConfig.Env("OK", null))).size());

        assertEquals(500, RuntimeConfig.cpuMillis("0.5", "cpu"));
        assertEquals(250, RuntimeConfig.cpuMillis("250m", "cpu"));
        assertEquals(1024, RuntimeConfig.memoryMiB("1Gi", "mem"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.memoryMiB("512MB", "mem"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validateResources("1", "500m", "128Mi", "512Mi"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validateResources("100m", "500m", "1Gi", "512Mi"));
        assertDoesNotThrow(() -> RuntimeConfig.validateResources("100m", "500m", "128Mi", "512Mi"));
    }
}
