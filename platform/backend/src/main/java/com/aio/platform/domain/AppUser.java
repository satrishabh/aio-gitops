package com.aio.platform.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @JsonIgnore public String subject;
    public String login;
    public String name;
    public String avatarUrl;
    public Instant createdAt = Instant.now();
    public Instant lastLoginAt;
}
