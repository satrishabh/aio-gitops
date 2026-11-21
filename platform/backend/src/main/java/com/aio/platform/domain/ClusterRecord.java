package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A Kubernetes cluster environments deploy to, as registered in Argo CD (by {@link #name}). No credentials here:
 * the token of a cluster added from the portal lives only in its Argo CD cluster Secret.
 */
@Entity
@Table(name = "clusters")
public class ClusterRecord {
    public static final String CONNECTED = "Connected", UNREACHABLE = "Unreachable", UNKNOWN = "Unknown";

    @Id public String name;
    public String cloud = "other";
    public String server;
    public String baseDomain;
    public String ingressIp;
    public String ingressClass;
    public String ingressController;
    public String ingressNamespace;
    public String issuer;
    public String dnsZone;
    public Boolean tls;
    public Boolean rollouts;
    public String status = UNKNOWN;
    public Instant lastCheckAt;
    public String lastError;
    public String createdBy = "system";
    public Instant createdAt = Instant.now();
}
