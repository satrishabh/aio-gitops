package com.aio.platform.saga;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One durable, orchestrated multi-system operation (see docs/architecture/AIO-V2-ARCHITECTURE.md §3.1).
 * The engine advances it one step at a time, outside any DB transaction, and persists the outcome with
 * optimistic locking. A crash or a scale-to-zero resumes from {@link #step}.
 */
@Entity
@Table(name = "saga_instances")
public class SagaInstance {
    public static final String RUNNING = "RUNNING", COMPENSATING = "COMPENSATING", SUCCEEDED = "SUCCEEDED",
            COMPENSATED = "COMPENSATED", FAILED = "FAILED",
            /** A step failed and the definition asked for manual intervention: waits for a human (or the timeout). */
            PAUSED = "PAUSED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String type;
    /** Idempotency key: starting a saga with an existing key returns the existing instance. */
    @Column(unique = true) public String businessKey;
    public String status = RUNNING;
    /** Index of the next step to execute (RUNNING) or to compensate (COMPENSATING). */
    public int step;
    @Column(columnDefinition = "TEXT") public String data = "{}";
    public int attempts;
    public Instant nextAttemptAt;
    public Instant deadlineAt;
    /** Lease: while in the future, another worker owns the current step. */
    public Instant lockedUntil;
    @Column(length = 1000) public String lastError;
    @Version public long version;
    public Instant createdAt;
    public Instant updatedAt;

    /** Doing work right now (keeps the platform awake). PAUSED is not active: it waits for a person, at no cost. */
    public boolean active() {
        return RUNNING.equals(status) || COMPENSATING.equals(status);
    }

    /** Not finished yet: active, or paused for intervention. */
    public boolean open() {
        return active() || PAUSED.equals(status);
    }
}
