package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** One Argo CD sync history entry for a project environment. */
@Entity
@Table(name = "deployments")
public class Deployment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long projectId;
    public String environment;
    public Long argoHistoryId;
    public String revision;
    public String imageTag;
    public Instant deployedAt;
}
