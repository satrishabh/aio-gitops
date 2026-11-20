package com.aio.platform.repo;

import com.aio.platform.domain.PlatformSwitch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSwitchRepo extends JpaRepository<PlatformSwitch, String> {
}
