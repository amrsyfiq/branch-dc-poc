package com.branchdc.dc.pass;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CheckpointRepository extends JpaRepository<Checkpoint, String> {

    List<Checkpoint> findAllByOrderByCodeAsc();

    /**
     * The checkpoint, locked until the transaction ends ({@code SELECT ... FOR UPDATE}). Requests for the
     * same branch then run one after the other, so a request that reads the data later also gets the
     * higher sequence numbers: "newer seq" really means "newer data".
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Checkpoint c where c.code = :code")
    Optional<Checkpoint> findForUpdate(String code);
}
