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
    @JsonIgnore public String webhookSecret;
    public Long webhookId;
    public String createdBy;
    public Instant createdAt = Instant.now();
}
