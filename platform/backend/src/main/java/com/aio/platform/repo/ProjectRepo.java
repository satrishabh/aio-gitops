package com.aio.platform.repo;

import com.aio.platform.domain.Project;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepo extends JpaRepository<Project, Long> {
    Optional<Project> findByName(String name);
}
