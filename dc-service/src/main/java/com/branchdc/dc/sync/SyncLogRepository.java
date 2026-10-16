package com.branchdc.dc.sync;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncLogRepository extends JpaRepository<SyncLog, String> {

    List<SyncLog> findByOrderByCreatedAtDesc(Limit limit);
}
