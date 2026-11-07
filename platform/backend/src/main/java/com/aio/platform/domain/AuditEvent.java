package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String actor;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(length = 500) public String actorSubject;
    public String action;
    public String target;
    public String details;
    public Instant createdAt = Instant.now();
}
