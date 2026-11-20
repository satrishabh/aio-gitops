package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class AssistantServiceTest {
    private static AssistantService on() {
        return new AssistantService("sk-ant-test-key-not-real", "claude-opus-5-5", "medium", 8);
    }

    @Test
    void secretsAreRedactedBeforeLeavingThePlatform() {
        String log = """
                Cloning with token ghp_abcdefghijklmnopqrstuvwxyz0123456789
                Authorization: Bearer abc.def-ghi_jkl.mnopqrstu
                DB_PASSWORD=hunter2 api_key: "x" AWS AKIAABCDEFGHIJKLMNOP
                ANTHROPIC sk-ant-api03-abcdefghij
                -----BEGIN RSA PRIVATE KEY-----
                MIIEvQIBADANBgkqhkiG9w0BAQEFAASC
                -----END RSA PRIVATE KEY-----
                build ok in 12s""";
        String out = AssistantService.redact(log);
        for (String secret : List.of("ghp_abcdef", "abc.def-ghi", "hunter2", "AKIAABCDEFGHIJKLMNOP", "sk-ant-api03", "MIIEvQ"))
            assertFalse(out.contains(secret), secret + " leaked: " + out);
        assertTrue(out.contains("DB_PASSWORD=[REDACTED]"), out);
        assertTrue(out.contains("build ok in 12s"), out);
    }

    @Test
    void requestUsesTheConfiguredModelToolsCachingAndRefusalFallback() {
        MessageCreateParams p = on().params(List.of(MessageParam.ofUser("why did my build fail?")));
        assertEquals("claude-opus-5-5", p.model().toString());
        assertEquals(16000L, p.maxTokens());
        assertEquals(AssistantService.TOOLS.size(), p.tools().orElseThrow().size());
        assertTrue(p.cacheControl().isPresent());
        assertEquals("default", p._additionalBodyProperties().get("fallbacks").convert(String.class));
        assertTrue(p._headers().values("anthropic-beta").contains("server-side-fallback-2026-07-01"));
        assertTrue(p.outputConfig().orElseThrow().effort().isPresent());
    }

    @Test
    void onlyReadOnlyToolsAndOneProposalTool() {
        var names = AssistantService.TOOLS.stream().map(t -> t.name()).toList();
        assertEquals(List.of("list_projects", "get_project", "list_runs", "get_run", "get_step_log", "read_repo_file",
                "list_connectors", "propose_action"), names);
        assertTrue(AssistantService.TOOLS.stream().allMatch(t -> t.strict().orElse(false)));
    }

    @Test
    void offWithoutAnApiKey() {
        AssistantService off = new AssistantService("", "claude-opus-5-5", "medium", 8);
        assertFalse(off.enabled());
        var e = assertThrows(IllegalStateException.class, () -> off.chat(null, "user-1", "hi", (n, i) -> ""));
        assertTrue(e.getMessage().contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void conversationsBelongToTheirOwner() {
        AssistantService a = on();
        assertThrows(NoSuchElementException.class, () -> a.decide("does-not-exist", "user-1", "p", true));
        assertThrows(IllegalArgumentException.class, () -> a.chat(null, "user-1", " ", (n, i) -> ""));
        assertThrows(IllegalArgumentException.class, () -> a.chat(null, "user-1", "x".repeat(8001), (n, i) -> ""));
    }

    @Test
    void actionsAreAFixedAllowList() {
        assertEquals(List.of("trigger_ci", "rerun_run", "test_connector", "enable_connector",
                "rollback_connector", "disable_connector"), AssistantService.ACTIONS);
        assertFalse(AssistantService.ACTIONS.contains("delete_project"));
        assertTrue(Map.of().isEmpty());
    }
}
