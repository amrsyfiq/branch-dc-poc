package com.branchdc.dc.pass;

import com.branchdc.events.TravelPass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PassRepository extends JpaRepository<Pass, String> {

    /** A branch's whole slice, in a stable order: what a sync sends. */
    List<Pass> findByCheckpointCodeOrderByApplicationNo(String checkpointCode);

    Page<Pass> findByCheckpointCode(String checkpointCode, Pageable pageable);

    long countByCheckpointCode(String checkpointCode);

    long countByCheckpointCodeAndStatus(String checkpointCode, TravelPass.Status status);
}
