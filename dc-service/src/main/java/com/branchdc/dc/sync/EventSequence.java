package com.branchdc.dc.sync;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.List;

/** The next values of the DC's {@code event_seq}: one per event, ascending, never reused. */
@Component
class EventSequence {

    private final EntityManager entityManager;

    EventSequence(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** {@code count} new sequence numbers in one round trip. */
    List<Long> next(int count) {
        if (count == 0) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Number> values = entityManager
                .createNativeQuery("select nextval('event_seq') from generate_series(1, :n)")
                .setParameter("n", count).getResultList();
        return values.stream().map(Number::longValue).sorted().toList();
    }
}
