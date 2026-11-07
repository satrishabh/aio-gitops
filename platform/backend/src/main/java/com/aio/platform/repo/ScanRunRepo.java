package com.aio.platform.repo;

import com.aio.platform.domain.ScanRun;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRunRepo extends JpaRepository<ScanRun, Long> {
    @org.springframework.data.jpa.repository.Query("select s from ScanRun s where s.projectId in :ids or (s.projectId is null and s.ownerSubject = :subject) order by s.id desc")
    List<ScanRun> findVisible(@org.springframework.data.repository.query.Param("ids") Collection<Long> ids,
        @org.springframework.data.repository.query.Param("subject") String subject, Pageable page);
    List<ScanRun> findByStatusIn(Collection<String> statuses);

    List<ScanRun> findAllByOrderByIdDesc(Pageable page);

    List<ScanRun> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    long countByTriggeredByAndStatusIn(String triggeredBy, Collection<String> statuses);
}
