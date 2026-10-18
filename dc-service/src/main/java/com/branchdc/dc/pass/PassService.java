package com.branchdc.dc.pass;

import com.branchdc.events.TravelPass;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.random.RandomGenerator;

/** Reads and creates the DC's travel passes. Entities stay inside this class; callers get DTOs or events. */
@Service
public class PassService {

    private final CheckpointRepository checkpoints;
    private final PassRepository passes;
    private final EntityManager entityManager;
    private final Clock clock;

    public PassService(CheckpointRepository checkpoints, PassRepository passes, EntityManager entityManager, Clock clock) {
        this.checkpoints = checkpoints;
        this.passes = passes;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** One page of passes, all branches or one, newest application number first. */
    @Transactional(readOnly = true)
    public PageView<PassView> page(String branchCode, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "applicationNo"));
        Page<Pass> result;
        if (branchCode == null) {
            result = passes.findAll(request);
        } else {
            requireCheckpoint(branchCode);
            result = passes.findByCheckpointCode(branchCode, request);
        }
        return new PageView<>(result.map(PassView::of).getContent(), page, size, result.getTotalElements());
    }

    /** Every checkpoint with its pass counts. */
    @Transactional(readOnly = true)
    public List<CheckpointCounts> counts() {
        return checkpoints.findAllByOrderByCodeAsc().stream().map(c -> new CheckpointCounts(c.getCode(), c.getName(),
                c.getKind(), passes.countByCheckpointCode(c.getCode()),
                passes.countByCheckpointCodeAndStatus(c.getCode(), TravelPass.Status.APPROVED),
                passes.countByCheckpointCodeAndStatus(c.getCode(), TravelPass.Status.REVOKED))).toList();
    }

    /** @throws UnknownBranchException if the DC has no such checkpoint */
    @Transactional(readOnly = true)
    public Checkpoint requireCheckpoint(String code) {
        return checkpoints.findById(code).orElseThrow(() -> new UnknownBranchException(code));
    }

    /**
     * Locks the checkpoint for the rest of the caller's transaction (see {@link CheckpointRepository#findForUpdate}).
     *
     * @throws UnknownBranchException if the DC has no such checkpoint
     */
    @Transactional
    public Checkpoint lockCheckpoint(String code) {
        return checkpoints.findForUpdate(code).orElseThrow(() -> new UnknownBranchException(code));
    }

    /** A branch's whole slice: exactly what is sent to that branch. */
    @Transactional(readOnly = true)
    public List<Pass> sliceOf(String code) {
        return passes.findByCheckpointCodeOrderByApplicationNo(code);
    }

    /** The holiday rush: {@code count} new approved passes for this checkpoint, saved in batches. */
    @Transactional
    public List<Pass> createRush(String code, int count, RandomGenerator random) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = LocalDateTime.ofInstant(now, clock.getZone()).toLocalDate();
        List<String> numbers = nextApplicationNumbers(count, today.getYear());
        List<TravelPass> generated = RushGenerator.generate(count, today, random);
        List<Pass> created = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> Pass.create(numbers.get(i), code, generated.get(i), now)).toList();
        return passes.saveAll(created);
    }

    /** {@code APP-2026-100001}, ... from the database sequence, so two rushes never pick the same number. */
    private List<String> nextApplicationNumbers(int count, int year) {
        @SuppressWarnings("unchecked")
        List<Number> values = entityManager
                .createNativeQuery("select nextval('application_no_seq') from generate_series(1, :n)")
                .setParameter("n", count).getResultList();
        return values.stream().map(v -> "APP-%d-%06d".formatted(year, v.longValue())).toList();
    }

    public record PageView<T>(List<T> items, int page, int size, long total) {
    }

    public record CheckpointCounts(String code, String name, Checkpoint.Kind kind, long passes, long approved,
                                   long revoked) {
    }

    /** A pass as the DC's API shows it (the DC sees every checkpoint's passes). */
    public record PassView(String applicationNo, String checkpoint, String passportNo, String fullName,
                           String nationality, LocalDate dateOfBirth, TravelPass.Type passType, LocalDate validFrom,
                           LocalDate validUntil, TravelPass.Status status, Instant updatedAt) {
        static PassView of(Pass p) {
            TravelPass t = p.toTravelPass();
            return new PassView(p.getApplicationNo(), p.getCheckpointCode(), t.passportNo(), t.fullName(),
                    t.nationality(), t.dateOfBirth(), t.passType(), t.validFrom(), t.validUntil(), t.status(),
                    p.getUpdatedAt());
        }
    }
}
