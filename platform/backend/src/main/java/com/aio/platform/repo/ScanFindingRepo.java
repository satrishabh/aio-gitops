package com.aio.platform.repo;

import com.aio.platform.domain.ScanFinding;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ScanFindingRepo extends JpaRepository<ScanFinding, Long> {
    /** Filtered page; empty filters ("") match everything. Ordered CRITICAL..UNKNOWN, then rule id. */
    @Query("""
            select f from ScanFinding f where f.scanId = :scanId
              and (:scanner = '' or f.scanner = :scanner)
              and (:severity = '' or f.severity = :severity)
              and (:q = '' or lower(concat(coalesce(f.ruleId, ''), ' ', coalesce(f.pkg, ''), ' ', coalesce(f.title, ''), ' ',
                   coalesce(f.target, ''))) like concat('%', :q, '%'))
            order by case f.severity when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 when 'LOW' then 3 else 4 end,
                     f.ruleId, f.id
            """)
    Page<ScanFinding> search(@Param("scanId") Long scanId, @Param("scanner") String scanner,
                             @Param("severity") String severity, @Param("q") String q, Pageable page);

    List<ScanFinding> findByScanIdOrderById(Long scanId);

    @Modifying
    @Transactional
    @Query("delete from ScanFinding f where f.scanId = :scanId")
    void deleteByScanId(@Param("scanId") Long scanId);
}
