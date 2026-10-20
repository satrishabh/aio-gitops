package com.aio.platform.repo;

import com.aio.platform.domain.Promotion;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRepo extends JpaRepository<Promotion, Long> {
    List<Promotion> findByStatusOrderByIdDesc(String status);

    List<Promotion> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    List<Promotion> findAllByOrderByIdDesc(Pageable page);
}
