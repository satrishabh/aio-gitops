package com.aio.platform.service;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.ScanRun;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.ScanRunRepo;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Standalone Trivy security scans, independent of CI/CD: any GitHub repository (public, or private when the
 * platform's token can read it), optionally a container image. Scanners run in parallel in the security-scan
 * Tekton pipeline; by default a scan only reports (FAIL_ON=NONE).
 */
@Service
public class ScanService {
    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    /** Only github.com: the clone runs inside the cluster, so arbitrary URLs must not be reachable through it. */
    static final Pattern GITHUB_URL = Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]{1,100})/([A-Za-z0-9_.-]{1,100}?)(?:\\.git)?/?$");
    static final Pattern REF = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}");
    /** registry/path:tag or @sha256 digest, lower-case path (OCI reference grammar, simplified). */
    static final Pattern IMAGE = Pattern.compile(
            "[a-z0-9]+(?:[._-][a-z0-9]+)*(?:\\.[a-z0-9-]+)*(?::[0-9]{1,5})?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[a-f0-9]{64})?");
    public static final List<String> SCANNERS = List.of("vuln", "secret", "misconfig", "license", "image");
    public static final Set<String> FAIL_ON = Set.of("NONE", "CRITICAL", "HIGH,CRITICAL", "MEDIUM,HIGH,CRITICAL");
    static final int MAX_ACTIVE_PER_USER = 3;

    private final ScanRunRepo scans;
    private final ProjectRepo projects;
    private final TektonService tekton;
    private final AuditService audit;

    public ScanService(ScanRunRepo scans, ProjectRepo projects, TektonService tekton, AuditService audit) {
        this.scans = scans;
        this.projects = projects;
        this.tekton = tekton;
        this.audit = audit;
    }

    /** repoUrl, or projectId to scan a project's repo; image optional (needed for the "image" scanner). */
    public record StartRequest(String repoUrl, Long projectId, String ref, String image, List<String> scanners,
                               String failOn, Boolean ignoreUnfixed) {}

    // ------------------------------------------------------------------ validation (pure, unit tested)

    static String normalizeRepo(String raw) {
        Matcher m = GITHUB_URL.matcher(raw == null ? "" : raw.trim());
        if (!m.matches() || m.group(2).equals(".") || m.group(2).equals(".."))
            throw new IllegalArgumentException("repository must look like https://github.com/<owner>/<repo>");
        return "https://github.com/" + m.group(1) + "/" + m.group(2) + ".git";
    }

    static String normalizeRef(String raw, String fallback) {
        String r = raw == null || raw.isBlank() ? fallback : raw.trim();
        if (r == null || !REF.matcher(r).matches() || r.contains(".."))
            throw new IllegalArgumentException("branch, tag or commit must look like main, v1.2.0 or a SHA");
        return r;
    }

    static String normalizeImage(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String i = raw.trim();
        if (i.length() > 500 || !IMAGE.matcher(i).matches())
            throw new IllegalArgumentException("image must look like ghcr.io/owner/app:tag");
        return i;
    }

    static Set<String> normalizeScanners(List<String> raw, String image) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw == null || raw.isEmpty() ? List.of("vuln", "secret", "misconfig") : raw) {
            String v = s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
            if (!SCANNERS.contains(v)) throw new IllegalArgumentException("unknown scanner '" + s + "' (use " + String.join(", ", SCANNERS) + ")");
            out.add(v);
        }
        if (out.contains("image") && image == null) throw new IllegalArgumentException("the image scanner needs an image");
        if (out.isEmpty()) throw new IllegalArgumentException("choose at least one scanner");
        return out;
    }

    static String normalizeFailOn(String raw) {
        String v = raw == null || raw.isBlank() ? "NONE" : raw.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        if (!FAIL_ON.contains(v)) throw new IllegalArgumentException("failOn must be one of " + FAIL_ON);
        return v;
    }

    // ------------------------------------------------------------------ lifecycle

    public ScanRun start(StartRequest req, String actor) {
        if (req == null) throw new IllegalArgumentException("repoUrl or projectId is required");
        Project p = req.projectId() == null ? null : projects.findById(req.projectId()).orElseThrow();
        String repo = normalizeRepo(p != null ? p.repoUrl : req.repoUrl());
        String ref = normalizeRef(req.ref(), p != null ? p.defaultBranch : "main");
        String image = normalizeImage(req.image());
        Set<String> scanners = normalizeScanners(req.scanners(), image);
        ScanRun s = new ScanRun();
        s.projectId = p == null ? null : p.id;
        s.repoUrl = repo;
        s.gitRef = ref;
        s.image = image;
        s.scanners = String.join(",", scanners);
        s.failOn = normalizeFailOn(req.failOn());
        s.ignoreUnfixed = Boolean.TRUE.equals(req.ignoreUnfixed());
        s.trigger = "MANUAL";
        return launch(s, scanners, p, actor);
    }

    public ScanRun rerun(ScanRun prev, String actor) {
        if (prev.isActive()) throw new IllegalArgumentException("scan #" + prev.id + " is still running");
        ScanRun s = new ScanRun();
        s.projectId = prev.projectId;
        s.repoUrl = prev.repoUrl;
        s.gitRef = prev.gitRef;   // same branch, not the old commit: a re-scan should see today's code and advisories
        s.image = prev.image;
        s.scanners = prev.scanners;
        s.failOn = prev.failOn;
        s.ignoreUnfixed = prev.ignoreUnfixed;
        s.trigger = "RERUN";
        s.rerunOf = prev.id;
        Project p = prev.projectId == null ? null : projects.findById(prev.projectId).orElse(null);
        return launch(s, new LinkedHashSet<>(List.of(prev.scanners.split(","))), p, actor);
    }

    private ScanRun launch(ScanRun s, Set<String> scanners, Project p, String actor) {
        if (scans.countByTriggeredByAndStatusIn(actor, ScanRun.ACTIVE) >= MAX_ACTIVE_PER_USER)
            throw new IllegalStateException("you already have " + MAX_ACTIVE_PER_USER + " scans running - wait for one to finish");
        s.triggeredBy = actor;
        s = scans.save(s);
        try {
            s.pipelineRunName = tekton.createScanRun(s, scanners, p == null ? null : p.name);
        } catch (Exception e) {
            log.warn("security scan {} could not start", s.id, e);
            s.status = CiRun.FAILED;
            s.message = "Could not create PipelineRun: " + TektonService.describe(e);
            s.finishedAt = Instant.now();
        }
        s = scans.save(s);
        audit.record(actor, "scan.start", "scan#" + s.id, s.repoUrl + "@" + s.gitRef + " [" + s.scanners + "]"
                + (s.image == null ? "" : " image=" + s.image));
        return s;
    }

    public void cancel(ScanRun s, String actor) {
        if (!s.isActive() || s.pipelineRunName == null) throw new IllegalArgumentException("scan is not running");
        tekton.cancel(s.pipelineRunName);
        audit.record(actor, "scan.cancel", "scan#" + s.id, s.pipelineRunName);
    }
}
