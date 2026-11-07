package com.aio.platform.repo;

import com.aio.platform.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepo extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findBySubject(String subject);
    Optional<AppUser> findByLogin(String login);
}
