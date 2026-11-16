package com.aio.platform.repo;

import com.aio.platform.domain.AssistantReport;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantReportRepo extends JpaRepository<AssistantReport, Long> {
    boolean existsByKindAndDay(String kind, LocalDate day);

    Optional<AssistantReport> findFirstByKindOrderByDayDesc(String kind);
}
