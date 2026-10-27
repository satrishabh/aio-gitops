package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aio.platform.domain.ScanFinding;
import com.aio.platform.domain.ScanRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ScanTest {

    // ------------------------------------------------------------------ validation

    @Test
    void acceptsOnlyGithubRepos() {
        assertEquals("https://github.com/OWASP/NodeGoat.git", ScanService.normalizeRepo("https://github.com/OWASP/NodeGoat"));
        assertEquals("https://github.com/a/b.git", ScanService.normalizeRepo(" https://github.com/a/b.git/ "));
        for (String bad : new String[]{null, "", "http://github.com/a/b", "https://gitlab.com/a/b", "https://github.com.evil.io/a/b",
                "https://github.com/a", "https://github.com/a/b/c", "https://github.com/a/..", "https://user@github.com/a/b",
                "file:///etc/passwd", "https://github.com/a/b?x=1"})
            assertThrows(IllegalArgumentException.class, () -> ScanService.normalizeRepo(bad), String.valueOf(bad));
    }

    @Test
    void validatesRefsAndImages() {
        assertEquals("main", ScanService.normalizeRef(null, "main"));
        assertEquals("release/v1.2", ScanService.normalizeRef("release/v1.2", "main"));
        for (String bad : new String[]{"-x", "a..b", "a b", "$(id)", "a;b"})
            assertThrows(IllegalArgumentException.class, () -> ScanService.normalizeRef(bad, "main"), bad);
        assertNull(ScanService.normalizeImage(" "));
        assertEquals("ghcr.io/o/app:1.0", ScanService.normalizeImage("ghcr.io/o/app:1.0"));
        assertThrows(IllegalArgumentException.class, () -> ScanService.normalizeImage("ghcr.io/o/app:1.0; rm -rf /"));
    }

    @Test
    void scannersDefaultAndNeedImage() {
        assertEquals(Set.of("vuln", "secret", "misconfig"), ScanService.normalizeScanners(null, null));
        assertEquals(Set.of("license"), ScanService.normalizeScanners(List.of("License"), null));
        assertThrows(IllegalArgumentException.class, () -> ScanService.normalizeScanners(List.of("image"), null));
        assertThrows(IllegalArgumentException.class, () -> ScanService.normalizeScanners(List.of("sast"), null));
        assertEquals("NONE", ScanService.normalizeFailOn(null));
        assertEquals("HIGH,CRITICAL", ScanService.normalizeFailOn("high, critical"));
        assertThrows(IllegalArgumentException.class, () -> ScanService.normalizeFailOn("LOW"));
    }

    @Test
    void pipelineParamsSwitchScannersOff() {
        ScanRun s = new ScanRun();
        s.repoUrl = "https://github.com/a/b.git";
        s.gitRef = "main";
        Map<String, String> p = TektonService.scanParams(s, Set.of("vuln", "image"));
        assertEquals("true", p.get("scan-vuln"));
        assertEquals("false", p.get("scan-secret"));
        assertEquals("false", p.get("scan-image"));   // no image given -> image task skipped
        assertEquals("NONE", p.get("fail-on"));
        s.image = "ghcr.io/a/b:1";
        assertEquals("true", TektonService.scanParams(s, Set.of("image")).get("scan-image"));
    }

    // ------------------------------------------------------------------ report parsing

    private static final String VULN = """
            {"Results":[{"Target":"package-lock.json","Vulnerabilities":[
              {"VulnerabilityID":"CVE-2021-1","PkgName":"lodash","InstalledVersion":"4.17.15","FixedVersion":"4.17.21","Severity":"HIGH","Title":"proto pollution","PrimaryURL":"https://avd/x"},
              {"VulnerabilityID":"CVE-2021-2","PkgName":"minimist","InstalledVersion":"1.2.0","Severity":"critical"}]}]}""".replace("\n", "");

    @Test
    void parsesVulnerabilitiesFromLastJsonLine() {
        var r = ScanWatcher.parseReport("2026 INFO downloading\n{\"Results\":[]}\n" + VULN + "\n", "vuln", 7L, 100);
        assertFalse(r.truncated());
        assertEquals(2, r.findings().size());
        ScanFinding f = r.findings().get(0);
        assertEquals(7L, f.scanId);
        assertEquals("CVE-2021-1", f.ruleId);
        assertEquals("lodash", f.pkg);
        assertEquals("4.17.21", f.fixedVersion);
        assertEquals("package-lock.json", f.target);
        assertEquals("CRITICAL", r.findings().get(1).severity);
        assertNull(r.findings().get(1).fixedVersion);
    }

    @Test
    void secretsNeverStoreTheMatch() {
        String log = "{\"Results\":[{\"Target\":\"config.js\",\"Secrets\":[{\"RuleID\":\"aws-access-key-id\",\"Category\":\"AWS\","
                + "\"Severity\":\"CRITICAL\",\"Title\":\"AWS Access Key ID\",\"StartLine\":3,\"EndLine\":3,\"Match\":\"AKIAABCDEFGHIJKLMNOP\"}]}]}";
        ScanFinding f = ScanWatcher.parseReport(log, "secret", 1L, 100).findings().get(0);
        assertEquals("aws-access-key-id", f.ruleId);
        assertEquals("L3", f.location);
        for (String v : new String[]{f.ruleId, f.pkg, f.title, f.location, f.target, f.url})
            assertFalse(v != null && v.contains("AKIA"), "secret value leaked: " + v);
    }

    @Test
    void misconfigSkipsPassesAndLicensesParse() {
        String mis = "{\"Results\":[{\"Target\":\"Dockerfile\",\"Misconfigurations\":["
                + "{\"AVDID\":\"AVD-DS-0002\",\"Title\":\"Root user\",\"Message\":\"Specify USER\",\"Severity\":\"HIGH\",\"Status\":\"FAIL\",\"CauseMetadata\":{\"StartLine\":1,\"EndLine\":4}},"
                + "{\"AVDID\":\"AVD-DS-0001\",\"Status\":\"PASS\",\"Severity\":\"LOW\"},"
                + "{\"ID\":\"DS026\",\"Message\":\"No HEALTHCHECK\",\"Severity\":\"LOW\",\"Status\":\"FAIL\"}]}]}";
        var m = ScanWatcher.parseReport(mis, "misconfig", 1L, 100).findings();
        assertEquals(2, m.size());
        assertEquals("Root user - Specify USER", m.get(0).title);
        assertEquals("L1-4", m.get(0).location);
        assertEquals("DS026", m.get(1).ruleId);
        assertEquals("No HEALTHCHECK", m.get(1).title);

        String lic = "{\"Results\":[{\"Target\":\"Node.js\",\"Licenses\":[{\"Name\":\"GPL-3.0\",\"PkgName\":\"x\",\"Category\":\"restricted\",\"Severity\":\"HIGH\",\"FilePath\":\"node_modules/x/package.json\"},"
                + "{\"Name\":\"WTFPL\",\"PkgName\":\"y\"}]}]}";
        var l = ScanWatcher.parseReport(lic, "license", 1L, 100).findings();
        assertEquals("restricted license", l.get(0).title);
        assertEquals("node_modules/x/package.json", l.get(0).target);
        assertEquals("license", l.get(1).title);
        assertEquals("UNKNOWN", l.get(1).severity);
    }

    @Test
    void capsFindingsAndToleratesGarbage() {
        var r = ScanWatcher.parseReport(VULN, "vuln", 1L, 1);
        assertEquals(1, r.findings().size());
        assertTrue(r.truncated());
        assertTrue(ScanWatcher.parseReport(null, "vuln", 1L, 10).findings().isEmpty());
        assertTrue(ScanWatcher.parseReport("{not json", "vuln", 1L, 10).findings().isEmpty());
    }

    @Test
    void parsesSummaryResult() {
        assertEquals(Map.of("critical", 1, "high", 2, "medium", 0, "low", 9, "unknown", 0),
                ScanWatcher.parseSummary("critical=1,high=2,medium=0,low=9,unknown=0\n"));
        assertEquals(Map.of("high", 3), ScanWatcher.parseSummary("high=3,bad,x=y"));
        assertTrue(ScanWatcher.readSummary(null).isEmpty());
    }

    // ------------------------------------------------------------------ Tekton YAML

    @Test
    @SuppressWarnings("unchecked")
    void securityPipelineIsWellFormed() throws Exception {
        Path tekton = Path.of("../../tekton");
        for (String f : List.of("tasks/trivy-db.yaml", "tasks/trivy-run.yaml", "tasks/git-clone.yaml"))
            assertNotNull(new Yaml().load(Files.readString(tekton.resolve(f))), f);
        Map<String, Object> p = new Yaml().load(Files.readString(tekton.resolve("pipelines/security-scan.yaml")));
        var tasks = (List<Map<String, Object>>) ((Map<String, Object>) p.get("spec")).get("tasks");
        var names = tasks.stream().map(t -> (String) t.get("name")).toList();
        assertEquals(List.of("clone", "trivy-db", "vuln", "secret", "misconfig", "license", "image"), names);
        for (var t : tasks) {
            for (String dep : (List<String>) t.getOrDefault("runAfter", List.of()))
                assertTrue(names.contains(dep), t.get("name") + " runs after unknown task " + dep);
            if (ScanWatcher.SCANNER_TASKS.contains((String) t.get("name"))) {
                assertNotNull(t.get("when"), t.get("name") + " must be switchable");
                assertFalse(((List<String>) t.getOrDefault("runAfter", List.of())).stream().anyMatch(ScanWatcher.SCANNER_TASKS::contains),
                        t.get("name") + " must run in parallel with the other scanners");
            }
        }
    }
}
