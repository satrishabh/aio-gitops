package com.aio.platform.web;

import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.service.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** GitHub webhook receiver. Authenticated by the per-project HMAC secret, not by session. */
@RestController
@RequestMapping("/api/webhooks/github")
public class WebhookController {
    private static final Set<String> PR_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ProjectRepo projects;
    private final RunService runs;
    private final ObjectMapper json;

    public WebhookController(ProjectRepo projects, RunService runs, ObjectMapper json) {
        this.projects = projects;
        this.runs = runs;
        this.json = json;
    }

    @PostMapping("/{projectId}")
    public ResponseEntity<String> receive(@PathVariable Long projectId,
                                          @RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                          @RequestHeader(value = "X-Hub-Signature-256", required = false) String sig,
                                          @RequestBody byte[] body) throws Exception {
        Project p = projects.findById(projectId).orElse(null);
        if (p == null || !validSignature(p.webhookSecret, body, sig)) return ResponseEntity.status(401).body("bad signature");

        JsonNode e = json.readTree(body);
        String sender = "github:" + e.path("sender").path("login").asText("unknown");
        if ("push".equals(event)) {
            if (!("refs/heads/" + p.defaultBranch).equals(e.path("ref").asText())) return ResponseEntity.ok("ignored ref");
            if (e.path("deleted").asBoolean(false)) return ResponseEntity.ok("ignored delete");
            runs.trigger(p, e.path("after").asText(), "PUSH", sender, true);
            return ResponseEntity.accepted().body("queued");
        }
        if ("pull_request".equals(event) && PR_ACTIONS.contains(e.path("action").asText())) {
            // GitHub exposes PR head commits (incl. forks) on the base repo, so fetching by SHA works
            String sha = e.path("pull_request").path("head").path("sha").asText();
            runs.trigger(p, sha, "PR", sender, false);
            return ResponseEntity.accepted().body("queued");
        }
        return ResponseEntity.ok("ignored event");
    }

    static boolean validSignature(String secret, byte[] body, String header) throws Exception {
        if (secret == null || header == null || !header.startsWith("sha256=")) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }
}
