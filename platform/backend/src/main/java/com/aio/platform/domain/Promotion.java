package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "promotions")
public class Promotion {
    public static final String PENDING = "PENDING", DONE = "DONE", REJECTED = "REJECTED", FAILED = "FAILED";

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
}
