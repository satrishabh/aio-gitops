package com.aio.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Text written by a scheduled assistant job (G6), e.g. the daily delivery summary. One per kind and day (unique), so
 * a job that runs again after a restart does not spend quota twice. The text is redacted before it is stored.
 */
@Entity
@Table(name = "assistant_reports")
public class AssistantReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String kind;
    public LocalDate day;
    public Instant createdAt = Instant.now();
    public String model;
    @Column(columnDefinition = "TEXT") public String text;
    /** Counts only (no names or messages): what the summary was based on. */
    @Column(length = 300) public String basis;
}
