package com.branchdc.dc.sync;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SyncLogRepository extends JpaRepository<SyncLog, UUID> {

    List<SyncLog> findByOrderByCreatedAtDesc(Limit limit);

    Optional<SyncLog> findFirstByBranchCodeOrderByCreatedAtDesc(String branchCode);

    /** The newest change the DC has handed to Kafka for this branch (the branch is caught up once it has it). */
    @Query("select max(l.lastSeq) from SyncLog l where l.branchCode = :branchCode and l.status = 'PUBLISHED'")
    Long lastPublishedSeq(String branchCode);
}
