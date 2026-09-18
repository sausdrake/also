package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataPaymentJpaRepository extends JpaRepository<JpaPaymentEntity, Long> {

    Optional<JpaPaymentEntity> findByMerchantReference(String merchantReference);

    Optional<JpaPaymentEntity> findByProviderTransactionId(String providerTransactionId);

    Optional<JpaPaymentEntity> findByCreationIdempotencyKey(String creationIdempotencyKey);

    /** Pessimistic row lock, same idiom as {@code SpringDataAuctionJpaRepository.findByIdWithLock}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM JpaPaymentEntity p WHERE p.id = :id")
    Optional<JpaPaymentEntity> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT p FROM JpaPaymentEntity p WHERE p.status IN :statuses AND p.createdAt < :cutoff")
    List<JpaPaymentEntity> findByStatusInAndCreatedAtBefore(@Param("statuses") List<PaymentStatus> statuses,
                                                            @Param("cutoff") OffsetDateTime cutoff);

    /**
     * P1.5 correction: filters on {@code updatedAt}, not {@code createdAt} - a SUCCEEDED
     * payment's {@code updatedAt} is frozen at the exact {@code markSucceeded} instant (proven
     * via full repo-wide audit during the P1.5 architecture gate: no domain method, mapper, JPA
     * callback, admin operation or scheduler can touch it afterward), whereas {@code createdAt}
     * can precede the actual confirmation instant by up to the full TTL (or longer, via the
     * EXPIRED -> SUCCEEDED late-success path) and silently misrepresents "confirmed in the last
     * 24h".
     */
    @Query("SELECT COALESCE(SUM(p.amountConfirmed), 0) FROM JpaPaymentEntity p "
            + "WHERE p.userId = :userId AND p.status = :status AND p.updatedAt >= :since")
    BigDecimal sumConfirmedByUserSince(@Param("userId") Long userId,
                                       @Param("status") PaymentStatus status,
                                       @Param("since") OffsetDateTime since);

    /**
     * P1.5: the combined, one-statement daily-limit capacity query - see
     * {@code PaymentRepository.calculateCapacityUsage} for why this must never be split into two
     * separate reads.
     */
    @Query("SELECT COALESCE(SUM(" +
            "CASE WHEN p.status = :succeededStatus THEN p.amountConfirmed " +
            "     WHEN p.status IN :activeStatuses THEN p.amountRequested " +
            "     ELSE 0 END), 0) " +
            "FROM JpaPaymentEntity p " +
            "WHERE p.userId = :userId " +
            "AND ((p.status = :succeededStatus AND p.updatedAt >= :since) OR p.status IN :activeStatuses)")
    BigDecimal calculateCapacityUsage(@Param("userId") Long userId,
                                      @Param("since") OffsetDateTime since,
                                      @Param("succeededStatus") PaymentStatus succeededStatus,
                                      @Param("activeStatuses") List<PaymentStatus> activeStatuses);

    /**
     * P1.5: expiry-sweep candidates - non-terminal payments whose {@code expiresAt} has passed.
     * Uses {@code expiresAt}, deliberately NOT {@code createdAt} (the existing
     * {@link #findByStatusInAndCreatedAtBefore} is a separate, unrelated, uncalled primitive -
     * not reused here, per the P1.5 architecture gate's explicit instruction). Returns bare ids
     * only - this read is not authoritative; see {@code PaymentRepository.findExpiredCandidateIds}.
     */
    @Query("SELECT p.id FROM JpaPaymentEntity p WHERE p.status IN :statuses AND p.expiresAt < :asOf")
    List<Long> findIdsByStatusInAndExpiresAtBefore(@Param("statuses") List<PaymentStatus> statuses,
                                                    @Param("asOf") OffsetDateTime asOf);

    /**
     * SUCCEEDED payments with a bound provider transaction but no matching
     * {@code deposit_transactions} row keyed {@code PAYMENT:<providerTransactionId>}. Native SQL:
     * the join target is another module's table and the key is a literal-prefixed concatenation,
     * neither of which JPQL expresses cleanly. {@code ||} concatenation is standard SQL and works
     * identically on PostgreSQL and H2 (PostgreSQL mode).
     */
    @Query(nativeQuery = true, value =
            "SELECT p.* FROM payment p "
            + "WHERE p.status = 'SUCCEEDED' "
            + "  AND p.provider_transaction_id IS NOT NULL "
            + "  AND NOT EXISTS ("
            + "    SELECT 1 FROM deposit_transactions dt "
            + "    WHERE dt.idempotency_key = 'PAYMENT:' || p.provider_transaction_id"
            + "  )")
    List<JpaPaymentEntity> findSucceededWithoutLedgerCredit();

    /** Phase 26: admin observability - payments that succeeded after their own expiry, newest first. */
    List<JpaPaymentEntity> findAllByLateSuccessAfterExpiryTrueOrderByUpdatedAtDesc();
}
