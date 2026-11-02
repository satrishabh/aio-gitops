package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** A custom domain or path route: {@code host + path} served by one project's environment. */
@Entity
@Table(name = "domains")
public class Domain {
    public static final String PENDING_DNS = "PENDING_DNS", ISSUING = "ISSUING", READY = "READY", FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long projectId;
    public String env;
    public String host;
    public String path = "/";
    public boolean stripPrefix;
    public boolean forceHttps = true;
    public boolean wwwRedirect;
    public boolean certOwner;
    public String status;
    public String message;
    public String issuer;
    public String dnsExpected;
    public Instant certExpiresAt;
    public Instant lastCheckedAt;
    public Instant readyAt;
    public String createdBy;
    public Instant createdAt = Instant.now();

    /**
     * Only routes whose DNS already points at the cluster are rendered (HTTP-01 needs the Ingress to be reachable).
     * FAILED stays routable: cert-manager keeps retrying through the same Ingress.
     */
    public boolean routable() {
        return ISSUING.equals(status) || READY.equals(status) || FAILED.equals(status);
    }

    public String url() {
        return "https://" + host + ("/".equals(path) ? "" : path);
    }
}
