package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** A delivery stage (dev, staging, prod, ...): where it runs and how images get there. */
@Entity
@Table(name = "environments")
public class Environment {
    @Id public String name;
    @Column(name = "position") public int position;
    public String promoteFrom;
    public String cluster;
    public String namespace;
    public boolean approvalRequired;
    public boolean spotAllowed;
    public boolean prodLike;
    public Instant createdAt = Instant.now();
}
