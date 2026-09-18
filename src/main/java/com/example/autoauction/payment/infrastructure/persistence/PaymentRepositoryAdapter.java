package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class PaymentRepositoryAdapter implements PaymentRepository {

    private static final List<PaymentStatus> NON_TERMINAL = List.of(PaymentStatus.CREATED, PaymentStatus.PENDING);
    private static final List<PaymentStatus> CONFIRMATION_POLLABLE = List.of(PaymentStatus.PENDING, PaymentStatus.EXPIRED);

    private final SpringDataPaymentJpaRepository jpaRepository;
    private final PaymentMapper mapper;

    public PaymentRepositoryAdapter(SpringDataPaymentJpaRepository jpaRepository, PaymentMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Payment save(Payment payment) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(payment)));
    }

    @Override
    @Transactional
    public Payment createAndFlush(Payment payment) {
        return mapper.toDomain(jpaRepository.saveAndFlush(mapper.toEntity(payment)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    /**
     * P1.4: missing {@code @Transactional} here (discovered during real-PostgreSQL concurrency
     * testing for P1.4's canonical confirmation operation) meant this call ran with no
     * transactional advice applied by this bean's own proxy - empirically proven, via a real
     * transaction trace, to let two concurrent callers both pass the lock unblocked, defeating
     * the exact serialization this method's own Javadoc promises. Mirrors the identity module's
     * own {@code IdentityVerificationRepositoryAdapter.findByIdWithLock}, which already carries
     * this annotation for the identical reason.
     */
    @Override
    @Transactional
    public Optional<Payment> findByIdForUpdate(Long id) {
        return jpaRepository.findByIdWithLock(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByMerchantReference(String merchantReference) {
        return jpaRepository.findByMerchantReference(merchantReference).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByProviderTransactionId(String providerTransactionId) {
        return jpaRepository.findByProviderTransactionId(providerTransactionId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByCreationIdempotencyKey(String creationIdempotencyKey) {
        return jpaRepository.findByCreationIdempotencyKey(creationIdempotencyKey).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> findNonTerminalOlderThan(OffsetDateTime cutoff) {
        return jpaRepository.findByStatusInAndCreatedAtBefore(NON_TERMINAL, cutoff).stream()
                .map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumSucceededConfirmedByUserSince(Long userId, OffsetDateTime since) {
        BigDecimal sum = jpaRepository.sumConfirmedByUserSince(userId, PaymentStatus.SUCCEEDED, since);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateCapacityUsage(Long userId, OffsetDateTime since) {
        BigDecimal sum = jpaRepository.calculateCapacityUsage(userId, since, PaymentStatus.SUCCEEDED, NON_TERMINAL);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findExpiredCandidateIds(OffsetDateTime asOf) {
        return jpaRepository.findIdsByStatusInAndExpiresAtBefore(NON_TERMINAL, asOf);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> findSucceededWithoutLedgerCredit() {
        return jpaRepository.findSucceededWithoutLedgerCredit().stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> findAllLateSuccessesNewestFirst() {
        return jpaRepository.findAllByLateSuccessAfterExpiryTrueOrderByUpdatedAtDesc().stream()
                .map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findConfirmationCandidateReferences() {
        return jpaRepository.findMerchantReferencesByStatusInAndProviderReferenceIsNotNull(CONFIRMATION_POLLABLE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findCreatedWithInitiationEvidenceCandidateIds() {
        return jpaRepository.findCreatedIdsWithInitiationEvidence();
    }
}
