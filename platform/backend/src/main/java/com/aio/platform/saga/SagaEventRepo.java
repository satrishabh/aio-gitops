package com.aio.platform.saga;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaEventRepo extends JpaRepository<SagaEvent, Long> {
    List<SagaEvent> findBySagaIdOrderByIdAsc(Long sagaId);
}
