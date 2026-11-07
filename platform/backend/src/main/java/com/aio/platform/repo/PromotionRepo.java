package com.aio.platform.repo;

import com.aio.platform.domain.Promotion;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRepo extends JpaRepository<Promotion, Long> {
    @org.springframework.data.jpa.repository.Query("select p from Promotion p where p.projectId in :ids and (:status is null or p.status = :status) order by p.id desc")
    List<Promotion> findVisible(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids,
        @org.springframework.data.repository.query.Param("status") String status, Pageable page);
    List<Promotion> findByStatusOrderByIdDesc(String status);

    List<Promotion> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    List<Promotion> findAllByOrderByIdDesc(Pageable page);
}
