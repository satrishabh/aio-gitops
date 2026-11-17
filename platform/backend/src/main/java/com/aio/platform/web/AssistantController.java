package com.aio.platform.web;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.RunStepRepo;
import com.aio.platform.security.UserScope;
import com.aio.platform.service.AssistantService;
import com.aio.platform.service.AssistantService.Proposal;
import com.aio.platform.service.GitHubClient;
import com.aio.platform.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

/**
 * The AIO assistant. Read-only tools reuse the portal's own endpoints and the same per-user project scope, so the
 * assistant sees exactly what the user sees. Proposed actions run only when the user approves them, through the same
 * endpoints as the buttons (same permission checks), and every decision is audited.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private static final Pattern REPO_PATH = Pattern.compile("^[A-Za-z0-9._/@+-]{1,300}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._/-]{1,200}$");

    private final AssistantService assistant;
    private final ProjectController projectsApi;
    private final RunController runsApi;
    private final ConnectorController connectorsApi;
    private final ProjectRepo projects;
    private final CiRunRepo runs;
    private final RunStepRepo steps;
    private final GitHubClient github;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AssistantController(AssistantService assistant, ProjectController projectsApi, RunController runsApi,
                               ConnectorController connectorsApi, ProjectRepo projects, CiRunRepo runs, RunStepRepo steps,
                               GitHubClient github, AuditService audit) {
        this.assistant = assistant;
        this.projectsApi = projectsApi;
        this.runsApi = runsApi;
        this.connectorsApi = connectorsApi;
        this.projects = projects;
        this.runs = runs;
        this.steps = steps;
        this.github = github;
        this.audit = audit;
    }

    public record ChatRequest(String conversationId, String message) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", assistant.enabled(), "model", assistant.model());
    }

    @PostMapping("/chat")
    public AssistantService.Reply chat(@RequestBody ChatRequest req, @AuthenticationPrincipal OAuth2User user) {
        String owner = owner(user);
        return assistant.chat(req.conversationId(), owner, req.message(), (name, input) -> tool(name, input, user));
    }

    @PostMapping("/{conversationId}/proposals/{proposalId}/approve")
    public Map<String, Object> approve(@PathVariable String conversationId, @PathVariable String proposalId,
                                       @AuthenticationPrincipal OAuth2User user) {
        Proposal p = assistant.decide(conversationId, owner(user), proposalId, true);
        audit.record(Me.login(user), "assistant.approve", p.action() + " " + p.targetId(), p.reason());
        Object result = execute(p, user);
        return Map.of("proposal", p, "result", result == null ? "done" : result);
    }

    @PostMapping("/{conversationId}/proposals/{proposalId}/reject")
    public Proposal reject(@PathVariable String conversationId, @PathVariable String proposalId,
                           @AuthenticationPrincipal OAuth2User user) {
        Proposal p = assistant.decide(conversationId, owner(user), proposalId, false);
        audit.record(Me.login(user), "assistant.reject", p.action() + " " + p.targetId(), p.reason());
        return p;
    }

    @DeleteMapping("/{conversationId}")
    public void forget(@PathVariable String conversationId, @AuthenticationPrincipal OAuth2User user) {
        assistant.forget(conversationId, owner(user));
    }

    private static String owner(OAuth2User user) {
        String s = UserScope.subject(user);
        if (s == null) throw new SecurityException("Sign in to use the assistant");
        return s;
    }

    /** Runs an approved proposal through the same endpoint as the matching button. */
    private Object execute(Proposal p, OAuth2User user) {
        String t = p.targetId();
        return switch (p.action()) {
            case "trigger_ci" -> {
                UserScope.project(projects, Long.valueOf(t));
                yield projectsApi.run(Long.valueOf(t), null, user);
            }
            case "rerun_run" -> {
                CiRun r = runs.findById(Long.valueOf(t)).orElseThrow();
                UserScope.project(projects, r.projectId);
                yield runsApi.rerun(r.id, null, user);
            }
            case "test_connector" -> connectorsApi.test(t);
            case "enable_connector" -> connectorsApi.enable(t, null, user);
            case "rollback_connector" -> connectorsApi.rollback(t, user);
            case "disable_connector" -> connectorsApi.disable(t, user);
            default -> throw new IllegalArgumentException("Unknown action " + p.action());
        };
    }

    // ------------------------------------------------------------------ read-only tools (user-scoped)

    String tool(String name, Map<String, Object> in, OAuth2User user) throws Exception {
        return switch (name) {
            case "list_projects" -> write(UserScope.projects(projects).stream().map(AssistantController::slim).toList());
            case "get_project" -> {
                Long id = id(in, "project_id");
                UserScope.project(projects, id);
                yield write(projectsApi.get(id));
            }
            case "list_runs" -> {
                int limit = Math.max(1, Math.min(30, ((Number) in.getOrDefault("limit", 10)).intValue()));
                String pid = String.valueOf(in.getOrDefault("project_id", "")).trim();
                List<Map<String, Object>> list = runsApi.list(pid.isEmpty() ? limit : 200);
                if (!pid.isEmpty()) {
                    Long id = Long.valueOf(pid);
                    UserScope.project(projects, id);
                    list = list.stream().filter(m -> id.equals(((CiRun) m.get("run")).projectId)).limit(limit).toList();
                }
                yield write(list);
            }
            case "get_run" -> {
                CiRun r = scopedRun(id(in, "run_id"));
                yield write(Map.of("run", r, "steps", steps.findByRunIdOrderById(r.id)));
            }
            case "get_step_log" -> {
                CiRun r = scopedRun(id(in, "run_id"));
                yield runsApi.logs(r.id, id(in, "step_id"));
            }
            case "read_repo_file" -> {
                Project p = UserScope.project(projects, id(in, "project_id"));
                String path = String.valueOf(in.get("path")).replaceFirst("^/+", "");
                if (!REPO_PATH.matcher(path).matches() || path.contains(".."))
                    throw new IllegalArgumentException("invalid path");
                String ref = String.valueOf(in.getOrDefault("ref", "")).trim();
                if (ref.isEmpty()) ref = p.defaultBranch == null ? "main" : p.defaultBranch;
                if (!REF.matcher(ref).matches() || ref.contains("..")) throw new IllegalArgumentException("invalid ref");
                var f = github.getFile(p.repoOwner, p.repoName, path, ref);
                yield f == null ? "File not found: " + path + " at " + ref : f.content();
            }
            case "list_connectors" -> write(connectorsApi.list());   // admin-only: throws for other users
            default -> throw new IllegalArgumentException("Unknown tool " + name);
        };
    }

    private CiRun scopedRun(Long id) {
        CiRun r = runs.findById(id).orElseThrow();
        UserScope.project(projects, r.projectId);
        return r;
    }

    private static Long id(Map<String, Object> in, String key) {
        String v = String.valueOf(in.get(key)).trim();
        if (!v.matches("\\d{1,18}")) throw new IllegalArgumentException(key + " must be a numeric id");
        return Long.valueOf(v);
    }

    private static Map<String, Object> slim(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("name", p.name);
        m.put("repository", p.repoOwner + "/" + p.repoName);
        m.put("defaultBranch", p.defaultBranch);
        return m;
    }

    private String write(Object o) throws Exception {
        return json.writeValueAsString(o);
    }
}
