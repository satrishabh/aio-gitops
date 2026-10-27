package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;

/** A standalone Trivy security scan of a GitHub repository (and optionally an image). */
@Entity
@Table(name = "scan_runs")
public class ScanRun {
    public static final Set<String> ACTIVE = CiRun.ACTIVE;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long projectId;
    public String repoUrl;
    public String gitRef;
    public String commitSha;
    public String image;
    public String scanners;
    public String failOn = "NONE";
    public boolean ignoreUnfixed;
    public String status = CiRun.PENDING;
    public String message;
    public String pipelineRunName;
    public String summary;          // JSON {scanner: {critical,high,medium,low,unknown}}
    public Integer findingsCount;
    public boolean findingsTruncated;
    public String trigger;
    public String triggeredBy;
    public Long rerunOf;
    public boolean logsArchived;
    public Instant createdAt = Instant.now();
    public Instant startedAt;
    public Instant finishedAt;

    public boolean isActive() {
        return ACTIVE.contains(status);
    }
}
