package com.branchdc.branch.store;

import com.branchdc.events.TravelPass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/** Reads of this branch's copy. Writes go through {@link PassWriter}. */
interface BranchPassRepository extends JpaRepository<BranchPass, String> {

    Page<BranchPass> findByDeletedFalse(Pageable pageable);

    List<BranchPass> findByPassportNoAndDeletedFalseOrderByValidFromDesc(String passportNo);

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(TravelPass.Status status);

    long countByDeletedTrue();

    long countByEventSeqBetween(long from, long to);

    @Query("select max(p.eventSeq) from BranchPass p")
    Long maxEventSeq();

    @Query("select max(p.receivedAt) from BranchPass p")
    Instant lastReceivedAt();
}
