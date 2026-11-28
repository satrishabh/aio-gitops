package com.aio.platform.web;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.service.RunService;
import com.aio.platform.service.WebhookInbox;
import com.aio.platform.service.WebhookInbox.Claim;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * GitHub webhook receiver. Authenticated by the per-project HMAC secret, not by session.
 * Deliveries are de-duplicated by X-GitHub-Delivery (see {@link WebhookInbox}) after the signature check,
 * so unsigned requests never reach the inbox.
 */
@RestController
@RequestMapping("/api/webhooks/github")
public class WebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Set<String> PR_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ProjectRepo projects;
    private final RunService runs;
    private final ObjectMapper json;
    private final WebhookInbox inbox;

    public WebhookController(ProjectRepo projects, RunService runs, ObjectMapper json, WebhookInbox inbox) {
        this.projects = projects;
        this.runs = runs;
        this.json = json;
        this.inbox = inbox;
    }

    @PostMapping("/{projectId}")
    public ResponseEntity<String> receive(@PathVariable Long projectId,
                                          @RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                          @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
                                          @RequestHeader(value = "X-Hub-Signature-256", required = false) String sig,
                                          @RequestBody byte[] body) throws Exception {
        Project p = projects.findById(projectId).orElse(null);
        if (p == null || !validSignature(p.webhookSecret, body, sig)) return ResponseEntity.status(401).body("bad signature");

        return switch (inbox.claim(p.id, delivery, event, body)) {
            case Claim.Replay r -> ResponseEntity.status(r.status()).header("X-AIO-Webhook-Replay", "true").body(r.body());
            // 429 makes the webhook buffer retry in 30 s; by then the first attempt has replied or gone stale
            case Claim.Busy b -> ResponseEntity.status(429).header("Retry-After", "30").body("delivery is being processed");
            case Claim.Conflict c -> ResponseEntity.status(409).body("delivery id already used for a different payload");
            case Claim.Untracked u -> handle(p, event, body).response();
            case Claim.Fresh f -> {
                Handled h;
                try {
                    h = handle(p, event, body);
                } catch (Exception e) {
                    inbox.release(f.delivery());
                    throw e;
                }
                try {
                    inbox.complete(f.delivery(), h.response().getStatusCode().value(), h.response().getBody(), h.runId());
                } catch (RuntimeException e) {
                    // The build has started: still answer success, or the sender would retry and start another one
                    log.warn("could not record reply for webhook delivery {} ({})", delivery, e.getClass().getSimpleName());
                }
                yield h.response();
            }
        };
    }

    private record Handled(ResponseEntity<String> response, Long runId) {
        static Handled of(ResponseEntity<String> response) { return new Handled(response, null); }
    }

    private Handled handle(Project p, String event, byte[] body) {
        JsonNode e = json.readTree(body);
        String sender = "github:" + e.path("sender").path("login").asString("unknown");
        if ("push".equals(event)) {
            if (!("refs/heads/" + p.defaultBranch).equals(e.path("ref").asString())) return Handled.of(ResponseEntity.ok("ignored ref"));
            if (e.path("deleted").asBoolean(false)) return Handled.of(ResponseEntity.ok("ignored delete"));
            CiRun run = runs.trigger(p, e.path("after").asString(), "PUSH", sender, true);
            return new Handled(ResponseEntity.accepted().body("queued"), run == null ? null : run.id);
        }
        if ("pull_request".equals(event) && PR_ACTIONS.contains(e.path("action").asString())) {
            // GitHub exposes PR head commits (incl. forks) on the base repo, so fetching by SHA works
            String sha = e.path("pull_request").path("head").path("sha").asString();
            CiRun run = runs.trigger(p, sha, "PR", sender, false);
            return new Handled(ResponseEntity.accepted().body("queued"), run == null ? null : run.id);
        }
        return Handled.of(ResponseEntity.ok("ignored event"));
    }

    static boolean validSignature(String secret, byte[] body, String header) throws Exception {
        if (secret == null || header == null || !header.startsWith("sha256=")) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }
}
