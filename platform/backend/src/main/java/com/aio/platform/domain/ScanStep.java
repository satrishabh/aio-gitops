package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scan_steps")
public class ScanStep {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long runId;
    public String taskName;
    public String stepName;
    public String status;       // WAITING | RUNNING | SUCCEEDED | FAILED | SKIPPED
    public String podName;
    public String containerName;
    public Integer exitCode;
    public Instant startedAt;
    public Instant finishedAt;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String logText;      // archived when the PipelineRun is pruned
}
