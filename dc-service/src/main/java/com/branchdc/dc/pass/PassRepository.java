package com.branchdc.dc.pass;

import com.branchdc.events.TravelPass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PassRepository extends JpaRepository<Pass, String> {

    /** A branch's whole slice as application numbers, in a stable order: what a sync or purge sends. */
    @Query("select p.applicationNo from Pass p where p.checkpointCode = :code order by p.applicationNo")
    List<String> findApplicationNosByCheckpointCode(@Param("code") String checkpointCode);

    Page<Pass> findByCheckpointCode(String checkpointCode, Pageable pageable);

    long countByCheckpointCode(String checkpointCode);

    long countByCheckpointCodeAndStatus(String checkpointCode, TravelPass.Status status);
}
