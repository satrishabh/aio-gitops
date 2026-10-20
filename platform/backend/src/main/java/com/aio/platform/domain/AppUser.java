package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String githubLogin;
    public String name;
    public String avatarUrl;
    public Instant createdAt = Instant.now();
    public Instant lastLoginAt;
}
