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

    /** Sum of {@code amountConfirmed} across the user's {@code SUCCEEDED} payments created at or after {@code since} (never null; zero when none). */
    BigDecimal sumSucceededConfirmedByUserSince(Long userId, OffsetDateTime since);

    /**
     * {@code SUCCEEDED} payments that have no corresponding {@code deposit_transactions} row keyed
     * {@code PAYMENT:<providerTransactionId>} - i.e. a confirmed payment whose ledger credit is
     * missing and must be re-applied idempotently by reconciliation.
     */
    List<Payment> findSucceededWithoutLedgerCredit();
}
