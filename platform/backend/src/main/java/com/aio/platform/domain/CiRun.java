package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "ci_runs")
public class CiRun {
    public static final String PENDING = "PENDING", RUNNING = "RUNNING", SUCCEEDED = "SUCCEEDED",
            FAILED = "FAILED", CANCELLED = "CANCELLED";
    public static final Set<String> ACTIVE = Set.of(PENDING, RUNNING);

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long projectId;
    public String pipelineRunName;
    public String trigger;      // MANUAL | PUSH | PR
    public String triggeredBy;
    public String gitRef;
    public String commitSha;
    public boolean deploy;
    public String status = PENDING;
    public String message;
    public String imageUrl;
    public String imageDigest;
    public Integer vulnCritical;
    public Integer vulnHigh;
    public Integer vulnMedium;
    public Integer vulnLow;
    public Instant createdAt = Instant.now();
    public Instant startedAt;
    public Instant finishedAt;

    public boolean isActive() {
        return ACTIVE.contains(status);
    }
}
