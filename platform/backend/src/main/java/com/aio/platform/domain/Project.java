package com.aio.platform.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "projects")
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    public String repoUrl;
    public String repoOwner;
    public String repoName;
    public String defaultBranch;
    public String dockerfile;
    public String contextDir;
    public String testImage;
    public String testCommand;
    public String imageRepo;
    public String scanSeverity = "HIGH,CRITICAL";   // HIGH,CRITICAL | CRITICAL | NONE
    /** JSON array of {name, value}; rendered as the chart's env: list in every environment. */
    @Column(columnDefinition = "text") public String envVars = "[]";
    public String cpuRequest = "50m";
    public String cpuLimit = "500m";
    public String memoryRequest = "128Mi";
    public String memoryLimit = "512Mi";
    @JsonIgnore public String webhookSecret;
    public Long webhookId;
    public String createdBy;
    public Instant createdAt = Instant.now();
}
