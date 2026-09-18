package com.example.autoauction.payment.domain.port;

import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseType;

import java.util.List;
import java.util.Optional;

/**
 * Slice 2: persistence port for {@link ReconciliationCase}. No arbitrary delete is exposed - a
 * case is opened and later resolved, never removed.
 */
public interface ReconciliationCaseRepository {

    ReconciliationCase save(ReconciliationCase reconciliationCase);

    Optional<ReconciliationCase> findById(Long id);

    Optional<ReconciliationCase> findByPaymentIdAndType(Long paymentId, ReconciliationCaseType type);

    List<ReconciliationCase> findOpen();

    /** All cases, newest-opened first ({@code openedAt DESC}), with {@code id DESC} as a
     * deterministic tiebreaker for coincident timestamps. Named explicitly (not {@code
     * findAll()}) so the ordering guarantee is visible at the call site, not hidden behind a
     * generic name. */
    List<ReconciliationCase> findAllNewestFirst();
}
