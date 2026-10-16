package com.branchdc.branch.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface AppliedEventRepository extends JpaRepository<AppliedEvent, String> {

    /** The event the current data came from (the newest one that was applied). */
    Optional<AppliedEvent> findFirstByOutcomeOrderByOccurredAtDesc(AppliedEvent.Outcome outcome);
}
