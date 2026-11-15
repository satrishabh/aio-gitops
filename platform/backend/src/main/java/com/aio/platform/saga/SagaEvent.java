package com.aio.platform.saga;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only history of a saga: one row per step outcome or status change (audit trail and UI timeline). */
@Entity
@Table(name = "saga_events")
public class SagaEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long sagaId;
    public String step;
    /** STARTED | DONE | WAIT | RETRY | FAIL | COMPENSATED | COMPENSATION_RETRY | SUCCEEDED | FAILED | TIMEOUT */
    public String outcome;
    @Column(length = 1000) public String message;
    public Instant at;

    static SagaEvent of(Long sagaId, String step, String outcome, String message, Instant at) {
        SagaEvent e = new SagaEvent();
        e.sagaId = sagaId;
        e.step = step;
        e.outcome = outcome;
        e.message = message == null ? null : (message.length() > 1000 ? message.substring(0, 1000) : message);
        e.at = at;
        return e;
    }
}
