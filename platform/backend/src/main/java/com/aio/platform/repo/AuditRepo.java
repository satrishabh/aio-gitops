package com.aio.platform.repo;

import com.aio.platform.domain.AuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepo extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findTop300ByOrderByIdDesc();
}
