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
        record(actor, action, target, details, com.aio.platform.security.UserScope.subject());
    }

    public void record(String actor, String action, String target, String details, String subject) {
        AuditEvent e = new AuditEvent();
        e.actor = actor == null ? "system" : actor;
        e.actorSubject = subject;
        e.action = action;
        e.target = target;
        e.details = details;
        repo.save(e);
    }
}
