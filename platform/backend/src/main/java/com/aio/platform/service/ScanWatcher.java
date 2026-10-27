package com.aio.platform.service;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.ScanFinding;
import com.aio.platform.domain.ScanRun;
import com.aio.platform.domain.ScanStep;
import com.aio.platform.repo.ScanFindingRepo;
import com.aio.platform.repo.ScanRunRepo;
import com.aio.platform.repo.ScanStepRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mirrors security-scan PipelineRuns into the database (status, steps, per-scanner summaries) and, when a scan
 * finishes, parses each scanner's JSON report (printed by its "report" step) into scan_findings.
 */
@Component
public class ScanWatcher {
    private static final Logger log = LoggerFactory.getLogger(ScanWatcher.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Pipeline tasks that are scanners (each has scan / report / gate steps). */
    static final List<String> SCANNER_TASKS = List.of("vuln", "secret", "misconfig", "license", "image");
    static final int MAX_FINDINGS = 5000, MAX_LOG_CHARS = 256 * 1024, KEEP_RUNS = 20;

    private final ScanRunRepo scans;
    private final ScanStepRepo steps;
    private final ScanFindingRepo findings;
    private final TektonService tekton;
    private final AuditService audit;

    public ScanWatcher(ScanRunRepo scans, ScanStepRepo steps, ScanFindingRepo findings, TektonService tekton, AuditService audit) {
        this.scans = scans;
        this.steps = steps;
        this.findings = findings;
        this.tekton = tekton;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.watch.scan-ms:4000}")
    public void poll() {
        for (ScanRun s : scans.findByStatusIn(ScanRun.ACTIVE)) {
            try {
                sync(s);
            } catch (Exception e) {
                log.warn("sync of scan {} failed: {}", s.id, e.getMessage());
            }
        }
    }

    void sync(ScanRun s) {
        if (s.pipelineRunName == null) return;
        GenericKubernetesResource pr = tekton.getPipelineRun(s.pipelineRunName);
        if (pr == null) {
            if (s.createdAt.isBefore(Instant.now().minus(Duration.ofMinutes(10)))) finish(s, CiRun.FAILED, "PipelineRun disappeared");
            return;
        }
        Object status = pr.getAdditionalProperties().get("status");
        if (status == null) return;
        s.startedAt = J.time(status, "startTime");
        Map<String, Map<String, Integer>> summary = readSummary(s.summary);
        for (Map<String, Object> child : J.list(status, "childReferences")) {
            if ("TaskRun".equals(child.get("kind"))) syncTaskRun(s, J.str(child, "pipelineTaskName"), J.str(child, "name"), summary);
        }
        for (Map<String, Object> skipped : J.list(status, "skippedTasks")) {
            ScanStep st = step(s.id, J.str(skipped, "name"), "-");
            st.status = "SKIPPED";
            steps.save(st);
        }
        s.summary = writeJson(summary);

        Map<String, Object> cond = J.list(status, "conditions").stream()
                .filter(c -> "Succeeded".equals(c.get("type"))).findFirst().orElse(null);
        String condStatus = J.str(cond, "status"), reason = J.str(cond, "reason");
        if ("True".equals(condStatus) || "False".equals(condStatus)) {
            s.finishedAt = J.time(status, "completionTime");
            boolean cancelled = reason != null && reason.startsWith("Cancelled");
            if (!cancelled) harvest(s);
            String msg = "True".equals(condStatus) ? "Scan complete"
                    : cancelled ? "Cancelled"
                    : "PipelineRunTimeout".equals(reason) ? "Stopped: the scan exceeded the time limit (" + tekton.timeout() + ")"
                    : gateFailed(s) ? "Findings at or above " + s.failOn + " (the fail-on gate)" : J.str(cond, "message");
            finish(s, "True".equals(condStatus) ? CiRun.SUCCEEDED : cancelled ? CiRun.CANCELLED : CiRun.FAILED, msg);
        } else {
            s.status = CiRun.RUNNING;
            s.message = J.str(cond, "message");
            scans.save(s);
        }
    }

    private boolean gateFailed(ScanRun s) {
        return steps.findByRunIdOrderById(s.id).stream().anyMatch(x -> "gate".equals(x.stepName) && "FAILED".equals(x.status));
    }

    private void syncTaskRun(ScanRun s, String taskName, String taskRunName, Map<String, Map<String, Integer>> summary) {
        GenericKubernetesResource tr = tekton.getTaskRun(taskRunName);
        if (tr == null) return;
        Object status = tr.getAdditionalProperties().get("status");
        String pod = J.str(status, "podName");
        for (Map<String, Object> st : J.list(status, "steps")) {
            ScanStep x = step(s.id, taskName, J.str(st, "name"));
            x.podName = pod;
            x.containerName = J.str(st, "container");
            if (st.get("terminated") != null) {
                Object exit = J.get(st, "terminated", "exitCode");
                x.exitCode = exit instanceof Number n ? n.intValue() : null;
                x.status = x.exitCode != null && x.exitCode == 0 ? "SUCCEEDED" : "FAILED";
                x.startedAt = J.time(st, "terminated", "startedAt");
                x.finishedAt = J.time(st, "terminated", "finishedAt");
            } else if (st.get("running") != null) {
                x.status = "RUNNING";
                x.startedAt = J.time(st, "running", "startedAt");
            } else {
                x.status = "WAITING";
            }
            steps.save(x);
        }
        for (Map<String, Object> r : J.list(status, "results")) {
            String name = J.str(r, "name"), value = J.str(r, "value");
            if (value == null) continue;
            if ("commit".equals(name)) s.commitSha = value.trim();
            if ("SUMMARY".equals(name) && SCANNER_TASKS.contains(taskName)) summary.put(taskName, parseSummary(value));
        }
    }

    // ------------------------------------------------------------------ findings

    /** Reads every scanner's report step and stores its findings (capped), replacing earlier ones. */
    void harvest(ScanRun s) {
        List<ScanFinding> all = new ArrayList<>();
        boolean truncated = false;
        for (ScanStep st : steps.findByRunIdOrderById(s.id)) {
            if (!"report".equals(st.stepName) || !SCANNER_TASKS.contains(st.taskName) || !"SUCCEEDED".equals(st.status)) continue;
            String text = tekton.logs(st.podName, st.containerName);
            ParseResult r = parseReport(text, st.taskName, s.id, MAX_FINDINGS - all.size());
            all.addAll(r.findings());
            truncated |= r.truncated();
        }
        findings.deleteByScanId(s.id);
        findings.saveAll(all);
        s.findingsCount = all.size();
        s.findingsTruncated = truncated;
    }

    record ParseResult(List<ScanFinding> findings, boolean truncated) {}

    /** Trivy JSON report (the last JSON line of the log) -> findings. Secret values ("Match") are never stored. */
    static ParseResult parseReport(String log, String scanner, Long scanId, int limit) {
        List<ScanFinding> out = new ArrayList<>();
        String json = null;
        if (log != null) for (String line : log.split("\\R")) if (line.startsWith("{")) json = line;
        if (json == null) return new ParseResult(out, false);
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            return new ParseResult(out, false);
        }
        boolean truncated = false;
        for (JsonNode res : root.path("Results")) {
            String target = res.path("Target").asText(null);
            for (JsonNode v : res.path("Vulnerabilities")) {
                if (out.size() >= limit) { truncated = true; break; }
                ScanFinding f = base(scanId, scanner, target, v.path("Severity").asText("UNKNOWN"));
                f.ruleId = v.path("VulnerabilityID").asText(null);
                f.pkg = v.path("PkgName").asText(null);
                f.installedVersion = v.path("InstalledVersion").asText(null);
                f.fixedVersion = v.path("FixedVersion").asText(null);
                f.title = v.path("Title").asText(null);
                f.url = v.path("PrimaryURL").asText(null);
                out.add(f);
            }
            for (JsonNode m : res.path("Misconfigurations")) {
                if ("PASS".equals(m.path("Status").asText())) continue;
                if (out.size() >= limit) { truncated = true; break; }
                ScanFinding f = base(scanId, scanner, target, m.path("Severity").asText("UNKNOWN"));
                f.ruleId = m.hasNonNull("AVDID") ? m.path("AVDID").asText() : m.path("ID").asText(null);
                String title = m.path("Title").asText(""), msg = m.path("Message").asText("");
                f.title = title.isEmpty() ? msg : msg.isEmpty() ? title : title + " - " + msg;
                f.url = m.path("PrimaryURL").asText(null);
                f.location = lines(m.path("CauseMetadata").path("StartLine"), m.path("CauseMetadata").path("EndLine"));
                out.add(f);
            }
            for (JsonNode sec : res.path("Secrets")) {
                if (out.size() >= limit) { truncated = true; break; }
                ScanFinding f = base(scanId, scanner, target, sec.path("Severity").asText("UNKNOWN"));
                f.ruleId = sec.path("RuleID").asText(null);
                f.pkg = sec.path("Category").asText(null);
                f.title = sec.path("Title").asText(null);   // never "Match": that is the secret itself
                f.location = lines(sec.path("StartLine"), sec.path("EndLine"));
                out.add(f);
            }
            for (JsonNode lic : res.path("Licenses")) {
                if (out.size() >= limit) { truncated = true; break; }
                ScanFinding f = base(scanId, scanner, lic.path("FilePath").asText(target), lic.path("Severity").asText("UNKNOWN"));
                f.ruleId = lic.path("Name").asText(null);
                f.pkg = lic.path("PkgName").asText(null);
                String cat = lic.path("Category").asText("");
                f.title = cat.isEmpty() ? "license" : cat + " license";
                f.url = lic.path("Link").asText(null);
                out.add(f);
            }
        }
        return new ParseResult(out, truncated);
    }

    private static ScanFinding base(Long scanId, String scanner, String target, String severity) {
        ScanFinding f = new ScanFinding();
        f.scanId = scanId;
        f.scanner = scanner;
        f.target = cut(target, 500);
        f.severity = severity == null ? "UNKNOWN" : severity.toUpperCase(java.util.Locale.ROOT);
        return f;
    }

    private static String lines(JsonNode start, JsonNode end) {
        if (!start.canConvertToInt() || start.asInt() <= 0) return null;
        return end.canConvertToInt() && end.asInt() > start.asInt() ? "L" + start.asInt() + "-" + end.asInt() : "L" + start.asInt();
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    // ------------------------------------------------------------------ summary JSON

    /** SUMMARY result: critical=0,high=2,medium=5,low=9,unknown=0 */
    static Map<String, Integer> parseSummary(String v) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String part : v.trim().split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            try { m.put(kv[0].trim().toLowerCase(java.util.Locale.ROOT), Integer.parseInt(kv[1].trim())); }
            catch (NumberFormatException e) { /* ignore */ }
        }
        return m;
    }

    /** The stored per-scanner summary JSON ({scanner: {critical: n, ...}}); empty map when unset or unreadable. */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Integer>> readSummary(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return JSON.readValue(json, LinkedHashMap.class); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private static String writeJson(Object o) {
        try { return JSON.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    private ScanStep step(Long runId, String task, String step) {
        return steps.findByRunIdAndTaskNameAndStepName(runId, task, step).orElseGet(() -> {
            ScanStep st = new ScanStep();
            st.runId = runId;
            st.taskName = task;
            st.stepName = step;
            return st;
        });
    }

    private void finish(ScanRun s, String status, String message) {
        s.status = status;
        s.message = message;
        if (s.finishedAt == null) s.finishedAt = Instant.now();
        scans.save(s);
        audit.record("system", "scan." + status.toLowerCase(java.util.Locale.ROOT), "scan#" + s.id,
                s.findingsCount == null ? null : s.findingsCount + " findings");
    }

    // ------------------------------------------------------------------ pruning

    /** Keeps the newest KEEP_RUNS finished scan PipelineRuns; older ones are deleted after archiving step logs. */
    @Scheduled(fixedDelayString = "${app.watch.cleanup-ms:600000}", initialDelayString = "${app.watch.cleanup-initial-ms:120000}")
    public void prune() {
        List<GenericKubernetesResource> all;
        try {
            all = new ArrayList<>(tekton.listScanRuns());
        } catch (RuntimeException e) {
            log.debug("scan prune skipped: {}", e.getMessage());
            return;
        }
        all.sort(Comparator.comparing((GenericKubernetesResource r) -> r.getMetadata().getCreationTimestamp()).reversed());
        int deleted = 0;
        for (int i = KEEP_RUNS; i < all.size(); i++) {
            var pr = all.get(i);
            String id = pr.getMetadata().getLabels().get("aio.platform/scan-id");
            ScanRun s;
            try { s = scans.findById(Long.valueOf(id)).orElse(null); } catch (NumberFormatException e) { s = null; }
            if (s != null && s.isActive()) continue;
            try {
                if (s != null && !s.logsArchived) archiveLogs(s);
                tekton.deletePipelineRun(pr.getMetadata().getName());
                deleted++;
            } catch (Exception e) {
                log.warn("prune of {} failed: {}", pr.getMetadata().getName(), e.getMessage());
            }
        }
        if (deleted > 0) audit.record("system", "cleanup.scanruns", null, "deleted " + deleted);
    }

    void archiveLogs(ScanRun s) {
        for (ScanStep st : steps.findByRunIdOrderById(s.id)) {
            if (st.logText != null || st.podName == null) continue;
            String text = tekton.logs(st.podName, st.containerName);
            st.logText = text.length() > MAX_LOG_CHARS ? text.substring(text.length() - MAX_LOG_CHARS) : text;
            steps.save(st);
        }
        s.logsArchived = true;
        scans.save(s);
    }
}
