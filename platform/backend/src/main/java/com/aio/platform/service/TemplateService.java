package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Template gallery + "create from template": AIO starters (our template repos), the user's own template repos,
 * and community templates found live via GitHub search ({@code template:true}).
 */
@Service
public class TemplateService {
    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern FULL_NAME = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}$");
    static final String TOPIC = "aio-template";
    static final List<String> STARTERS = List.of("react-vite", "nextjs", "spring-boot", "fastapi", "go-api");

    /** Category chip -> search terms (combined with template:true). */
    static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("ui-dashboard", "dashboard");
        CATEGORIES.put("admin", "admin template");
        CATEGORIES.put("landing", "landing page");
        CATEGORIES.put("react-vite", "react vite");
        CATEGORIES.put("nextjs", "nextjs");
        CATEGORIES.put("vue", "vue");
        CATEGORIES.put("spring-boot", "spring boot");
        CATEGORIES.put("fastapi", "fastapi");
        CATEGORIES.put("go", "go api");
        CATEGORIES.put("node-express", "express");
    }

    private final GitHubClient github;
    private final ProjectService projectService;
    private final RunService runService;
    private final ProjectRepo projects;
    private final AuditService audit;
    private final String owner;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Instant at, List<Map<String, Object>> items) {}

    public TemplateService(GitHubClient github, ProjectService projectService, RunService runService, ProjectRepo projects,
                           AuditService audit, AppProperties props) {
        this.github = github;
        this.projectService = projectService;
        this.runService = runService;
        this.projects = projects;
        this.audit = audit;
        this.owner = props.gitops().owner();
    }

    // ---------------------------------------------------------------- catalog

    public List<Map<String, Object>> list(String source, String q, String category, String language) {
        String src = source == null ? "starters" : source;
        return switch (src) {
            case "starters" -> cached("starters", Duration.ofMinutes(10), this::starters);
            case "mine" -> cached("mine", Duration.ofMinutes(1), () -> search(
                    "user:" + owner + " topic:" + TOPIC + " template:true", "mine").stream()
                    .filter(t -> !String.valueOf(t.get("name")).startsWith("aio-template-")).toList());
            case "community" -> {
                String query = communityQuery(category, q, language);
                yield cached("c:" + query, Duration.ofMinutes(10), () -> search(query, "community"));
            }
            default -> throw new IllegalArgumentException("source must be starters, mine or community");
        };
    }

    private List<Map<String, Object>> starters() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String s : STARTERS) {
            try {
                Map<String, Object> t = card(github.getRepo(owner, "aio-template-" + s), "starters");
                t.put("aioReady", true);
                t.put("stack", s);
                out.add(t);
            } catch (HttpClientErrorException.NotFound e) {
                log.warn("starter template {}/aio-template-{} not published (run bootstrap/publish-templates.ps1)", owner, s);
            }
        }
        return out;
    }

    private List<Map<String, Object>> search(String query, String source) {
        return github.searchRepos(query, 30).stream().map(r -> card(r, source)).toList();
    }

    /** Builds the GitHub search query. User text is reduced to plain words so it cannot inject other qualifiers. */
    static String communityQuery(String category, String text, String language) {
        List<String> parts = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            String clean = text.replaceAll("[^A-Za-z0-9 .+#-]", " ").replaceAll("\\s+", " ").trim();
            if (clean.length() > 80) clean = clean.substring(0, 80);
            if (!clean.isEmpty()) parts.add(clean);
        }
        if (category != null && !category.isBlank()) {
            String c = CATEGORIES.get(category);
            if (c == null) throw new IllegalArgumentException("unknown category: " + category);
            parts.add(c);
        }
        if (language != null && !language.isBlank()) {
            if (!language.matches("[A-Za-z0-9+#-]{1,30}")) throw new IllegalArgumentException("invalid language");
            parts.add("language:" + language);
        }
        parts.add("template:true");
        parts.add("archived:false");
        parts.add("stars:>10");
        return String.join(" ", parts);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> card(Map<String, Object> r, String source) {
        Map<String, Object> t = new LinkedHashMap<>();
        Map<String, Object> ownerMap = r.get("owner") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> lic = r.get("license") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        t.put("fullName", r.get("full_name"));
        t.put("owner", ownerMap.get("login"));
        t.put("name", r.get("name"));
        t.put("avatarUrl", ownerMap.get("avatar_url"));
        t.put("description", r.get("description") == null ? "" : r.get("description"));
        t.put("url", r.get("html_url"));
        t.put("homepage", r.get("homepage"));
        t.put("language", r.get("language"));
        t.put("stars", r.getOrDefault("stargazers_count", 0));
        t.put("license", lic == null ? null : lic.get("spdx_id"));
        t.put("pushedAt", r.get("pushed_at"));
        t.put("topics", r.getOrDefault("topics", List.of()));
        t.put("isTemplate", Boolean.TRUE.equals(r.get("is_template")));
        t.put("private", Boolean.TRUE.equals(r.get("private")));
        t.put("source", source);
        return t;
    }

    private List<Map<String, Object>> cached(String key, Duration ttl, java.util.function.Supplier<List<Map<String, Object>>> load) {
        Cached c = cache.get(key);
        if (c != null && c.at().plus(ttl).isAfter(Instant.now())) return c.items();
        List<Map<String, Object>> items = load.get();
        if (cache.size() > 200) cache.clear();
        cache.put(key, new Cached(Instant.now(), items));
        return items;
    }

    // ---------------------------------------------------------------- inspection

    /** What the pipeline needs to know about a template before creating from it. */
    public record Inspection(String fullName, String description, String license, boolean isTemplate, String stack,
                             boolean hasDockerfile, boolean canContainerize, String note, Map<String, String> files) {}

    @SuppressWarnings("unchecked")
    public Inspection inspect(String fullName) {
        String[] or = split(fullName);
        Map<String, Object> repo = github.getRepo(or[0], or[1]);
        List<String> entries = github.rootEntries(or[0], or[1]);
        if (entries == null) entries = List.of();
        String pkg = null;
        if (entries.contains("package.json")) {
            var f = github.getFile(or[0], or[1], "package.json", String.valueOf(repo.getOrDefault("default_branch", "main")));
            pkg = f == null ? null : f.content();
        }
        Stack s = detect(entries, pkg);
        boolean hasDockerfile = entries.contains("Dockerfile");
        Map<String, String> files = hasDockerfile ? Map.of() : generatedFiles(s, entries);
        String note;
        if (hasDockerfile) note = "Uses the template's own Dockerfile. The app must listen on 8080 and answer GET /health.";
        else if (!files.isEmpty()) note = "A Dockerfile for " + s.label + " will be committed (nginx on 8080 with /health).";
        else note = "No Dockerfile, and " + s.label + " cannot be containerized automatically. Pick a template with a Dockerfile, "
                + "or create it and add one (port 8080, GET /health).";
        Map<String, Object> lic = repo.get("license") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return new Inspection(String.valueOf(repo.get("full_name")),
                repo.get("description") == null ? "" : String.valueOf(repo.get("description")),
                lic == null ? null : String.valueOf(lic.get("spdx_id")),
                Boolean.TRUE.equals(repo.get("is_template")), s.label, hasDockerfile,
                hasDockerfile || !files.isEmpty(), note, files);
    }

    /** Detected stack; {@code outDir} is set for static front-ends that the platform can serve with nginx. */
    record Stack(String label, String outDir, String install, String build) {
        static final Stack UNKNOWN = new Stack("unknown stack", null, null, null);
    }

    static Stack detect(List<String> entries, String packageJson) {
        if (packageJson != null) {
            Set<String> deps = new HashSet<>();
            boolean hasBuild = false;
            try {
                JsonNode n = JSON.readTree(packageJson);
                for (String k : List.of("dependencies", "devDependencies")) n.path(k).fieldNames().forEachRemaining(deps::add);
                hasBuild = n.path("scripts").has("build");
            } catch (Exception e) {
                return new Stack("Node.js (unreadable package.json)", null, null, null);
            }
            String pm = entries.contains("pnpm-lock.yaml") ? "pnpm" : entries.contains("yarn.lock") ? "yarn" : "npm";
            String install = switch (pm) {
                case "pnpm" -> "corepack enable && pnpm install";
                case "yarn" -> "corepack enable && yarn install";
                default -> entries.contains("package-lock.json") ? "npm ci" : "npm install --no-audit --no-fund";
            };
            String build = pm + " run build";
            if (deps.contains("next")) return new Stack("Next.js", null, install, build);
            if (deps.contains("@angular/core")) return new Stack("Angular", null, install, build);
            if (!hasBuild) return new Stack("Node.js server", null, install, null);
            if (deps.contains("react-scripts")) return new Stack("React (Create React App)", "build", install, build);
            if (deps.contains("astro")) return new Stack("Astro", "dist", install, build);
            if (deps.contains("vite")) {
                String label = deps.contains("vue") ? "Vue + Vite" : deps.contains("svelte") ? "Svelte + Vite"
                        : deps.contains("react") ? "React + Vite" : "Vite";
                return new Stack(label, "dist", install, build);
            }
            if (deps.contains("@vue/cli-service")) return new Stack("Vue CLI", "dist", install, build);
            return new Stack("Node.js", null, install, build);
        }
        if (entries.contains("pom.xml")) return new Stack("Java (Maven)", null, null, null);
        if (entries.contains("build.gradle") || entries.contains("build.gradle.kts")) return new Stack("Java (Gradle)", null, null, null);
        if (entries.contains("requirements.txt") || entries.contains("pyproject.toml")) return new Stack("Python", null, null, null);
        if (entries.contains("go.mod")) return new Stack("Go", null, null, null);
        if (entries.contains("index.html")) return new Stack("Static HTML", ".", null, null);
        return Stack.UNKNOWN;
    }

    static final String NGINX_CONF = """
            # Added by the AIO platform: static site on 8080 with /health for Kubernetes probes
            server {
              listen 8080;
              root /usr/share/nginx/html;
              location = /health { default_type application/json; return 200 '{"status":"UP"}'; }
              location / { try_files $uri $uri/ /index.html; }
            }
            """;

    /** Files to commit so a static front-end runs on the platform; empty when the stack is not auto-supported. */
    static Map<String, String> generatedFiles(Stack s, List<String> entries) {
        if (s.outDir() == null) return Map.of();
        Map<String, String> files = new LinkedHashMap<>();
        String runtime = """
                FROM nginxinc/nginx-unprivileged:stable-alpine
                USER root
                RUN apk upgrade --no-cache
                COPY .aio/nginx.conf /etc/nginx/conf.d/default.conf
                """;
        String dockerfile;
        if (s.build() == null) {
            dockerfile = "# Added by the AIO platform: serve the static site with unprivileged nginx on 8080\n" + runtime
                    + "COPY . /usr/share/nginx/html\nUSER 101\nEXPOSE 8080\n";
        } else {
            dockerfile = "# Added by the AIO platform (" + s.label() + "): build with Node, serve with unprivileged nginx on 8080\n"
                    + "FROM node:22-alpine AS build\nWORKDIR /src\nCOPY . .\n"
                    + "RUN " + s.install() + " && " + s.build() + "\n\n" + runtime
                    + "COPY --from=build /src/" + s.outDir() + " /usr/share/nginx/html\nUSER 101\nEXPOSE 8080\n";
        }
        files.put("Dockerfile", dockerfile);
        files.put(".aio/nginx.conf", NGINX_CONF);
        if (!entries.contains(".dockerignore")) files.put(".dockerignore", "node_modules\n.git\n");
        return files;
    }

    // ---------------------------------------------------------------- create

    public record CreateRequest(String template, String name, Boolean privateRepo, String scanSeverity, String description) {}

    public Map<String, Object> create(CreateRequest req, String actor) {
        String[] t = split(req.template());
        String name = ProjectService.sanitizeName(req.name() == null || req.name().isBlank() ? t[1] : req.name());
        if (projects.findByName(name).isPresent()) throw new IllegalArgumentException("project already exists: " + name);
        if (github.repoExists(owner, name)) throw new IllegalArgumentException("GitHub repo already exists: " + owner + "/" + name);

        Inspection ins = inspect(req.template());
        if (!ins.isTemplate()) throw new IllegalArgumentException(req.template() + " is not a GitHub template repository");
        if (!ins.canContainerize()) throw new IllegalArgumentException(ins.note());

        boolean priv = req.privateRepo() == null || req.privateRepo();
        Map<String, Object> repo = github.generateFromTemplate(t[0], t[1], owner, name, priv,
                req.description() == null || req.description().isBlank() ? "Created from " + req.template() + " via AIO" : req.description());
        String branch = String.valueOf(repo.getOrDefault("default_branch", "main"));
        waitForContents(name);

        List<String> added = new ArrayList<>();
        for (var f : ins.files().entrySet()) {
            github.putFile(owner, name, f.getKey(), branch, f.getValue(), null, "chore: add " + f.getKey() + " for the AIO pipeline");
            added.add(f.getKey());
        }

        Project p = projectService.create(new ProjectService.CreateRequest("https://github.com/" + owner + "/" + name,
                name, null, null, null, null, true, req.scanSeverity(), null, null, null, null, null), actor);
        CiRun run = runService.trigger(p, null, "TEMPLATE", actor, true);
        audit.record(actor, "template.create", name, "from " + req.template() + (added.isEmpty() ? "" : " +" + added));
        cache.remove("mine");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", p);
        out.put("run", run);
        out.put("repoUrl", "https://github.com/" + owner + "/" + name);
        out.put("addedFiles", added);
        return out;
    }

    /** Template generation copies files asynchronously; wait until the new repo has content. */
    private void waitForContents(String repo) {
        for (int i = 0; i < 30; i++) {
            List<String> e = github.rootEntries(owner, repo);
            if (e != null && !e.isEmpty()) return;
            try { Thread.sleep(2000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
        }
        throw new IllegalStateException("repository " + owner + "/" + repo + " was created but its files did not appear in time");
    }

    /** Marks the project's repo as a GitHub template with the aio-template topic so it shows under "Your templates". */
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveAsTemplate(Project p, String actor) {
        github.setTemplate(p.repoOwner, p.repoName, true);
        Set<String> topics = new LinkedHashSet<>();
        Object existing = github.getRepo(p.repoOwner, p.repoName).get("topics");
        if (existing instanceof List<?> l) l.forEach(x -> topics.add(String.valueOf(x)));
        topics.add(TOPIC);
        github.replaceTopics(p.repoOwner, p.repoName, new ArrayList<>(topics));
        cache.remove("mine");
        audit.record(actor, "template.save", p.name, p.repoOwner + "/" + p.repoName);
        return Map.of("template", p.repoOwner + "/" + p.repoName, "topics", topics);
    }

    static String[] split(String fullName) {
        if (fullName == null || !FULL_NAME.matcher(fullName.trim()).matches() || fullName.contains(".."))
            throw new IllegalArgumentException("template must look like owner/repo");
        return fullName.trim().split("/", 2);
    }
}
