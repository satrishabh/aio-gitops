package com.aio.platform.repo;

import com.aio.platform.domain.ClusterRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClusterRecordRepo extends JpaRepository<ClusterRecord, String> {
}
