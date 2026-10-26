package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlueGreenTest {

    @Test
    void enablesBlueGreenOnceAndKeepsCustomBlocks() {
        String yaml = "image:\n  tag: \"abc\"\nreplicaCount: 1\n";
        String on = GitOpsService.withBlueGreen(yaml, "shop-preview.example.io");
        assertTrue(on.contains("blueGreen:\n  enabled: true\n  previewHost: shop-preview.example.io\n"), on);
        assertEquals(on, GitOpsService.withBlueGreen(on, "other"), "idempotent");
        String custom = yaml + "blueGreen:\n  enabled: false\n";
        assertEquals(custom, GitOpsService.withBlueGreen(custom, "x"), "an explicit opt-out is respected");
        assertFalse(GitOpsService.withBlueGreen(yaml, null).contains("previewHost"));
    }

    @Test
    void parsesAPausedBlueGreenRollout() {
        Map<String, Object> rollout = Map.of(
                "spec", Map.of("template", Map.of("spec", Map.of("containers",
                        List.of(Map.of("name", "app", "image", "ghcr.io/acme/shop:new123"))))),
                "status", Map.of("phase", "Paused", "message", "BlueGreenPause",
                        "pauseConditions", List.of(Map.of("reason", "BlueGreenPause")),
                        "blueGreen", Map.of("activeSelector", "aaa", "previewSelector", "bbb")));
        var raw = RolloutService.parse(rollout);
        assertTrue(raw.paused());
        assertTrue(raw.split());
        assertEquals("new123", raw.specTag());
        assertEquals("Paused", raw.phase());

        var promoted = RolloutService.parse(Map.of("status", Map.of("phase", "Healthy",
                "blueGreen", Map.of("activeSelector", "bbb", "previewSelector", "bbb"))));
        assertFalse(promoted.split());
        assertFalse(promoted.paused());
    }
}
