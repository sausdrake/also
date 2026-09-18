package com.example.autoauction.payment.domain.port;

import com.example.autoauction.payment.domain.Payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Slice 2: persistence port for {@link Payment}. The domain/application layer depends on this
 * interface only - never on Spring Data / JPA.
 */
public interface PaymentRepository {

    Payment save(Payment payment);

    /**
     * P1.5: narrowly-scoped creation-only primitive - identical to {@link #save} except it
     * flushes immediately, so a unique-constraint violation (idempotency-key or, astronomically
     * unlikely, merchant-reference collision) surfaces deterministically inside the creation
     * call itself rather than being deferred past the point where the caller has already decided
     * how to respond. Deliberately NOT a change to {@link #save}'s existing semantics - every
     * other caller (confirmation, expiry) keeps using {@link #save} exactly as before.
     */
    Payment createAndFlush(Payment payment);

    Optional<Payment> findById(Long id);

    /**
     * Pessimistic ({@code SELECT ... FOR UPDATE}) load, mirroring this project's
     * {@code findByIdWithLock} idiom (e.g. {@code AuctionRepository}). Used by the future
     * confirmation/reconciliation path so two concurrent confirmations of one payment serialise.
     */
    Optional<Payment> findByIdForUpdate(Long id);

    Optional<Payment> findByMerchantReference(String merchantReference);

    Optional<Payment> findByProviderTransactionId(String providerTransactionId);

    Optional<Payment> findByCreationIdempotencyKey(String creationIdempotencyKey);

    /** Non-terminal payments ({@code CREATED} / {@code PENDING}) created before the cutoff - the reconciliation sweep's candidate set. */
    List<Payment> findNonTerminalOlderThan(OffsetDateTime cutoff);

    /**
     * Sum of {@code amountConfirmed} across the user's {@code SUCCEEDED} payments CONFIRMED
     * (i.e. {@code updatedAt}, not {@code createdAt} - see {@link com.example.autoauction.payment.domain.Payment#getUpdatedAt()}
     * and the P1.5 architecture gate that proved {@code updatedAt} is frozen at the exact
     * {@code markSucceeded} instant for a terminal SUCCEEDED row) at or after {@code since}
     * (never null; zero when none). Not used by the P1.5 creation capacity check itself (see
     * {@link #calculateCapacityUsage}) - kept correct for any other consumer.
     */
    BigDecimal sumSucceededConfirmedByUserSince(Long userId, OffsetDateTime since);

    /**
     * P1.5: the ONE-atomic-statement daily-limit capacity check - {@code SUCCEEDED.amountConfirmed}
     * (by {@code updatedAt >= since}) PLUS {@code amountRequested} of every currently
     * non-terminal ({@code CREATED}/{@code PENDING}) payment for this user, combined in a single
     * SQL statement so both components are read from the same database snapshot. Deliberately
     * NOT implemented as two separate repository calls summed in application code - two separate
     * statements inside one READ COMMITTED transaction can observe a concurrent confirmation's
     * commit between them and silently undercount total usage (proven during the P1.5
     * architecture challenge). Must be called while holding {@code DepositRepository}'s
     * per-user lock to serialize against concurrent creation for the same user.
     */
    BigDecimal calculateCapacityUsage(Long userId, OffsetDateTime since);

    /**
     * P1.5: candidate ids for the expiry sweep - non-terminal ({@code CREATED}/{@code PENDING})
     * payments whose {@code expiresAt} has already passed. Deliberately returns bare ids, not
     * entities: this read is NOT authoritative (a candidate may already have been confirmed or
     * expired by a concurrent operation by the time the sweep processes it) - the caller MUST
     * re-read each one under {@link #findByIdForUpdate} and recheck its status before mutating.
     */
    List<Long> findExpiredCandidateIds(OffsetDateTime asOf);

    /**
     * {@code SUCCEEDED} payments that have no corresponding {@code deposit_transactions} row keyed
     * {@code PAYMENT:<providerTransactionId>} - i.e. a confirmed payment whose ledger credit is
     * missing and must be re-applied idempotently by reconciliation.
     */
    List<Payment> findSucceededWithoutLedgerCredit();

    /**
     * Phase 26: admin observability only - payments whose {@code lateSuccessAfterExpiry} flag is
     * set (an authentic EXPIRED -&gt; SUCCEEDED provider report, already credited exactly once;
     * see {@link Payment#markSucceeded}), newest-succeeded-first ({@code updatedAt DESC} - the
     * already-proven success-instant timestamp for a terminal SUCCEEDED row, see {@link
     * #sumSucceededConfirmedByUserSince}'s own Javadoc). Read-only; no caller may mutate the
     * returned rows through this method.
     */
    List<Payment> findAllLateSuccessesNewestFirst();
}
