package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final Pattern GITHUB_URL =
            Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");
    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9_./-]{1,300}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectRepo projects;
    private final GitHubClient github;
    private final GitOpsService gitops;
    private final ArgoCdService argo;
    private final ClusterService cluster;
    private final AuditService audit;
    private final AppProperties props;

    public ProjectService(ProjectRepo projects, GitHubClient github, GitOpsService gitops, ArgoCdService argo,
                          ClusterService cluster, AuditService audit, AppProperties props) {
        this.projects = projects;
        this.github = github;
        this.gitops = gitops;
        this.argo = argo;
        this.cluster = cluster;
        this.audit = audit;
        this.props = props;
    }

    public record CreateRequest(String repoUrl, String name, String dockerfile, String contextDir,
                                String testImage, String testCommand, Boolean registerWebhook, String scanSeverity) {}

    public Project updateSettings(Project p, String scanSeverity, String testImage, String testCommand, String actor) {
        StringBuilder changes = new StringBuilder();
        if (scanSeverity != null && !scanSeverity.equals(p.scanSeverity)) {
            p.scanSeverity = validSeverity(scanSeverity);
            changes.append("scanSeverity=").append(p.scanSeverity).append(' ');
        }
        if (testImage != null && !testImage.isBlank() && !testImage.trim().equals(p.testImage)) {
            p.testImage = testImage.trim();
            changes.append("testImage=").append(p.testImage).append(' ');
        }
        if (testCommand != null && !testCommand.trim().equals(p.testCommand)) {
            p.testCommand = testCommand.trim();
            changes.append("testCommand changed ");
        }
        if (changes.isEmpty()) return p;
        audit.record(actor, "project.settings", p.name, changes.toString().trim());
        return projects.save(p);
    }

    /**
     * Tears everything down. Each step is best-effort so a half-broken project can still be removed;
     * problems are returned as warnings (and audited) instead of aborting.
     */
    public java.util.List<String> delete(Project p, String actor) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        for (String env : GitOpsService.ENVS) {
            try { argo.deleteApp(ArgoCdService.appName(p, env)); }
            catch (Exception e) { warnings.add("argo app " + env + ": " + e.getMessage()); }
        }
        // dev/staging/prod are shared: the Argo cascade above removes only this project's release.
        for (String env : GitOpsService.ENVS) {
            try { cluster.deleteLegacyNamespace(p, env); }
            catch (Exception e) { warnings.add("legacy namespace " + env + ": " + e.getMessage()); }
        }
        try { gitops.deleteProjectFiles(p); }
        catch (Exception e) { warnings.add("gitops files: " + e.getMessage()); }
        if (p.webhookId != null) {
            try { github.deleteWebhook(p.repoOwner, p.repoName, p.webhookId); }
            catch (Exception e) { warnings.add("webhook: " + e.getMessage()); }
        }
        projects.delete(p); // runs, steps, deployments, env status, promotions cascade in the DB
        audit.record(actor, "project.delete", p.name, warnings.isEmpty() ? "clean" : String.join("; ", warnings));
        return warnings;
    }

    static String validSeverity(String s) {
        String v = s.trim().toUpperCase(java.util.Locale.ROOT);
        if (!RunService.SEVERITIES.contains(v)) throw new IllegalArgumentException("scanSeverity must be one of " + RunService.SEVERITIES);
        return v;
    }

    public Project create(CreateRequest req, String actor) {
        Matcher m = GITHUB_URL.matcher(req.repoUrl() == null ? "" : req.repoUrl().trim());
        if (!m.matches()) throw new IllegalArgumentException("repoUrl must look like https://github.com/<owner>/<repo>");
        String owner = m.group(1), repoName = m.group(2);

        String name = sanitizeName(req.name() == null || req.name().isBlank() ? repoName : req.name());
        if (projects.findByName(name).isPresent()) throw new IllegalArgumentException("project already exists: " + name);

        Map<String, Object> repo = github.getRepo(owner, repoName); // fails fast if not reachable with our token

        Project p = new Project();
        p.name = name;
        p.repoOwner = owner;
        p.repoName = repoName;
        p.repoUrl = "https://github.com/" + owner + "/" + repoName + ".git";
        p.defaultBranch = String.valueOf(repo.getOrDefault("default_branch", "main"));
        p.dockerfile = safePath(req.dockerfile(), "./Dockerfile");
        p.contextDir = safePath(req.contextDir(), ".");
        p.testImage = req.testImage() == null || req.testImage().isBlank() ? "alpine:3.20" : req.testImage().trim();
        p.testCommand = req.testCommand() == null ? "" : req.testCommand().trim();
        p.imageRepo = props.registry() + "/" + name;
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        p.webhookSecret = HexFormat.of().formatHex(secret);
        p.createdBy = actor;
        p.scanSeverity = req.scanSeverity() == null || req.scanSeverity().isBlank() ? "HIGH,CRITICAL" : validSeverity(req.scanSeverity());
        p = projects.save(p);

        for (String env : GitOpsService.ENVS) cluster.ensureEnvironment(p, env);
        gitops.initProject(p);
        for (String env : GitOpsService.ENVS) argo.upsertApp(p, env);

        if (Boolean.TRUE.equals(req.registerWebhook()) && props.publicUrl() != null && !props.publicUrl().isBlank()) {
            try {
                p.webhookId = github.createWebhook(owner, repoName,
                        props.publicUrl().replaceAll("/$", "") + "/api/webhooks/github/" + p.id, p.webhookSecret);
                p = projects.save(p);
            } catch (Exception e) {
                log.warn("webhook registration failed for {}: {}", name, e.getMessage());
                audit.record(actor, "project.webhook.failed", name, e.getMessage());
            }
        }
        audit.record(actor, "project.create", name, p.repoUrl);
        return p;
    }

    static String sanitizeName(String raw) {
        String n = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (n.length() > 40) n = n.substring(0, 40).replaceAll("-$", "");
        if (n.isEmpty()) throw new IllegalArgumentException("invalid project name");
        return n;
    }

    private static String safePath(String v, String def) {
        if (v == null || v.isBlank()) return def;
        String s = v.trim();
        if (!SAFE_PATH.matcher(s).matches() || s.contains("..")) throw new IllegalArgumentException("invalid path: " + v);
        return s;
    }
}
