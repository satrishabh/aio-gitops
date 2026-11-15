package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "promotions")
public class Promotion {
    /** APPLYING: approved (or auto-applied) and being carried out by the "promotion" saga. */
    public static final String PENDING = "PENDING", APPLYING = "APPLYING", DONE = "DONE", REJECTED = "REJECTED", FAILED = "FAILED",
            /** PAUSED: a step failed and the environment's failure strategy is manual intervention; an approver decides. */
            PAUSED = "PAUSED";

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
    /** Tag the target env had before this change; the saga restores it if the change fails. */
    public String previousTag;
    public Long sagaId;
    /** Optimistic lock: a stale copy can never overwrite a newer decision. */
    @Version public long version;
}
