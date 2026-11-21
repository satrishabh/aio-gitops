package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "promotions")
public class Promotion {
    /**
     * PENDING (waiting for approval) -> DEPLOYING (tag committed, target not yet confirmed healthy) -> DONE | FAILED.
     * REJECTED ends a PENDING request. Rows from before DEPLOYING existed keep their status.
     */
    public static final String PENDING = "PENDING", DEPLOYING = "DEPLOYING", DONE = "DONE", REJECTED = "REJECTED", FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long projectId;
    public String fromEnv;
    public String toEnv;
    public String imageTag;
    public String kind;         // PROMOTE | ROLLBACK
    public String status;
    public String requestedBy;
    public String decidedBy;
    public String message;
    public Instant requestedAt = Instant.now();
    public Instant decidedAt;
    /** When the tag was committed and the health watch started (null for rows that never deployed). */
    public Instant deployingSince;
    /** Why a deploy failed: last log lines of the failing container (bounded), shown under the reason. */
    @Column(columnDefinition = "text")
    public String detail;
    /** Copy the source env's runtime settings into the target values with the tag (applied on approval for prod). */
    public Boolean copySettings;
}
