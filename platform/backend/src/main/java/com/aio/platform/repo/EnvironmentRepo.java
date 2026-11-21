package com.aio.platform.repo;

import com.aio.platform.domain.Environment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepo extends JpaRepository<Environment, String> {
    List<Environment> findAllByOrderByPositionAsc();
}
