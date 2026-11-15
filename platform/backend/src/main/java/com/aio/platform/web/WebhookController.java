package com.aio.platform.web;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.service.RunService;
import com.aio.platform.service.WebhookInbox;
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

/**
 * GitHub webhook receiver. Authenticated by the per-project HMAC secret, not by session.
 * Idempotent: a delivery id seen before is acknowledged without work (inbox), and a commit that is already
 * building or built is not built again.
 */
@RestController
@RequestMapping("/api/webhooks/github")
public class WebhookController {
    private static final Set<String> PR_ACTIONS = Set.of("opened", "synchronize", "reopened");

    static final Set<String> BUILT_OR_BUILDING = Set.of(CiRun.PENDING, CiRun.RUNNING, CiRun.SUCCEEDED);

    private final ProjectRepo projects;
    private final RunService runs;
    private final ObjectMapper json;
    private final WebhookInbox inbox;
    private final CiRunRepo runRepo;

    public WebhookController(ProjectRepo projects, RunService runs, ObjectMapper json, WebhookInbox inbox, CiRunRepo runRepo) {
        this.projects = projects;
        this.runs = runs;
        this.json = json;
        this.inbox = inbox;
        this.runRepo = runRepo;
    }

    @PostMapping("/{projectId}")
    public ResponseEntity<String> receive(@PathVariable Long projectId,
                                          @RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                          @RequestHeader(value = "X-Hub-Signature-256", required = false) String sig,
                                          @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
                                          @RequestBody byte[] body) throws Exception {
        Project p = projects.findById(projectId).orElse(null);
        if (p == null || !validSignature(p.webhookSecret, body, sig)) return ResponseEntity.status(401).body("bad signature");
        boolean tracked = delivery != null && !delivery.isBlank();
        if (tracked && !inbox.firstTime(delivery, p.id, event)) return ResponseEntity.ok("duplicate delivery " + delivery);
        try {
            ResponseEntity<String> r = handle(p, event, body);
            if (tracked) inbox.outcome(delivery, r.getStatusCode().value() + " " + r.getBody());
            return r;
        } catch (Exception failure) {
            if (tracked) inbox.forget(delivery);   // let GitHub's or the buffer's retry through
            throw failure;
        }
    }

    private ResponseEntity<String> handle(Project p, String event, byte[] body) throws Exception {
        JsonNode e = json.readTree(body);
        String sender = "github:" + e.path("sender").path("login").asText("unknown");
        if ("push".equals(event)) {
            if (!("refs/heads/" + p.defaultBranch).equals(e.path("ref").asText())) return ResponseEntity.ok("ignored ref");
            if (e.path("deleted").asBoolean(false)) return ResponseEntity.ok("ignored delete");
            String sha = e.path("after").asText();
            if (runRepo.existsByProjectIdAndGitRefAndDeployAndStatusIn(p.id, sha, true, BUILT_OR_BUILDING))
                return ResponseEntity.ok("commit already built or building");
            runs.trigger(p, sha, "PUSH", sender, true);
            return ResponseEntity.accepted().body("queued");
        }
        if ("pull_request".equals(event) && PR_ACTIONS.contains(e.path("action").asText())) {
            // GitHub exposes PR head commits (incl. forks) on the base repo, so fetching by SHA works
            String sha = e.path("pull_request").path("head").path("sha").asText();
            if (runRepo.existsByProjectIdAndGitRefAndDeployAndStatusIn(p.id, sha, false, BUILT_OR_BUILDING))
                return ResponseEntity.ok("commit already built or building");
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
