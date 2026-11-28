package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** One GitHub webhook delivery (X-GitHub-Delivery) received for a project, and the reply AIO gave it. */
@Entity
@Table(name = "webhook_deliveries", uniqueConstraints = @UniqueConstraint(
        name = "webhook_deliveries_project_delivery_uq", columnNames = {"project_id", "delivery_id"}))
public class WebhookDelivery {
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long projectId;
    public String deliveryId;
    public String event;
    public String payloadSha256;
    public String status = PROCESSING;
    public Integer httpStatus;
    public String response;
    public Long runId;
    public int attempts = 1;
    public Instant receivedAt = Instant.now();
    public Instant claimedAt = Instant.now();
    public Instant completedAt;
}
