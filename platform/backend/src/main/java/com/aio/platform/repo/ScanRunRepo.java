package com.aio.platform.repo;

import com.aio.platform.domain.ScanRun;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRunRepo extends JpaRepository<ScanRun, Long> {
    List<ScanRun> findByStatusIn(Collection<String> statuses);

    List<ScanRun> findAllByOrderByIdDesc(Pageable page);

    List<ScanRun> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    long countByTriggeredByAndStatusIn(String triggeredBy, Collection<String> statuses);
}
