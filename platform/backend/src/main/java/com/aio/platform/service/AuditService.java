package com.aio.platform.service;

import com.aio.platform.domain.AuditEvent;
import com.aio.platform.repo.AuditRepo;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepo repo;

    public AuditService(AuditRepo repo) {
        this.repo = repo;
    }

    public void record(String actor, String action, String target, String details) {
        AuditEvent e = new AuditEvent();
        e.actor = actor == null ? "system" : actor;
        e.action = action;
        e.target = target;
        e.details = details;
        repo.save(e);
    }
}
