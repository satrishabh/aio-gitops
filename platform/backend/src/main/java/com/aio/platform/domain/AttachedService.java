package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A connector a project uses in one environment (table {@code project_services}). The values file in git is
 * authoritative; this row records who attached it and with which options (JSON object of strings).
 */
@Entity
@Table(name = "project_services")
public class AttachedService {
    public static final String ATTACHED = "ATTACHED", FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long projectId;
    public String connectorId;
    public String env;
    public String options = "{}";
    public String status;
    public String detail;
    /** scope shared: name of the Secret copied into the env namespace (deleted on detach). */
    public String copiedSecret;
    public String createdBy;
    public Instant createdAt = Instant.now();
}
