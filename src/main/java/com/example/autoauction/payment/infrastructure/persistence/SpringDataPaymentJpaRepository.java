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

    @Query("SELECT COALESCE(SUM(p.amountConfirmed), 0) FROM JpaPaymentEntity p "
            + "WHERE p.userId = :userId AND p.status = :status AND p.createdAt >= :since")
    BigDecimal sumConfirmedByUserSince(@Param("userId") Long userId,
                                       @Param("status") PaymentStatus status,
                                       @Param("since") OffsetDateTime since);

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
}
