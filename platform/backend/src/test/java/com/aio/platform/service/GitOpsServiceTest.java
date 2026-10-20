package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aio.platform.domain.CiRun;
import org.junit.jupiter.api.Test;

class GitOpsServiceTest {

    private static final String YAML = """
            image:
              repository: ghcr.io/acme/shop
              tag: "0.0.0"
            replicaCount: 1
            """;

    @Test
    void extractsAndReplacesTag() {
        assertEquals("0.0.0", GitOpsService.extractTag(YAML));
        String updated = GitOpsService.replaceTag(YAML, "abc123def456");
        assertEquals("abc123def456", GitOpsService.extractTag(updated));
        assertTrue(updated.contains("repository: ghcr.io/acme/shop"));
        assertTrue(updated.contains("replicaCount: 1"));
    }

    @Test
    void handlesUnquotedTagWrittenByYq() {
        String yq = YAML.replace("\"0.0.0\"", "1a2b3c");
        assertEquals("1a2b3c", GitOpsService.extractTag(yq));
    }

    @Test
    void rejectsUnsafeTags() {
        assertThrows(IllegalArgumentException.class, () -> GitOpsService.replaceTag(YAML, "x\"; rm -rf"));
    }

    @Test
    void parsesScanSummary() {
        CiRun r = new CiRun();
        PipelineWatcher.applyScanSummary(r, "critical=1,high=2,medium=3,low=4");
        assertEquals(1, r.vulnCritical);
        assertEquals(2, r.vulnHigh);
        assertEquals(3, r.vulnMedium);
        assertEquals(4, r.vulnLow);
    }

    @Test
    void sanitizesProjectNames() {
        assertEquals("my-cool-app", ProjectService.sanitizeName("My_Cool.App"));
    }
}
