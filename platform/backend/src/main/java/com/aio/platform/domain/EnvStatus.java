package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "env_status")
public class EnvStatus {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long projectId;
    public String environment;
    public String syncStatus;
    public String healthStatus;
    public String imageTag;
    public String revision;
    public Instant updatedAt;
}
