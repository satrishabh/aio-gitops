package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "run_steps")
public class RunStep {
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
}
