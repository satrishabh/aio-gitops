package com.aio.platform.domain;

import jakarta.persistence.*;

/** One Trivy finding: a vulnerable package, a leaked secret, a misconfiguration or a license. */
@Entity
@Table(name = "scan_findings")
public class ScanFinding {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long scanId;
    public String scanner;
    public String target;
    public String ruleId;
    public String pkg;
    public String installedVersion;
    public String fixedVersion;
    public String severity;
    public String title;
    public String url;
    public String location;
}
