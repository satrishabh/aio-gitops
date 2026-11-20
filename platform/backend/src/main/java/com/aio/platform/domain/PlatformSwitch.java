package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** An on/off switch for a platform feature held off until an admin switches it on. No row means off. */
@Entity
@Table(name = "platform_switches")
public class PlatformSwitch {
    @Id public String name;
    public boolean enabled;
    public String changedBy;
    public Instant changedAt = Instant.now();
}
