package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.WithdrawalRequest;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import com.example.autoauction.deposit.domain.port.WithdrawalRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class WithdrawalRequestRepositoryAdapter implements WithdrawalRequestRepository {

    private final SpringDataWithdrawalRequestJpaRepository jpaRepository;

    public WithdrawalRequestRepositoryAdapter(SpringDataWithdrawalRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public WithdrawalRequest save(WithdrawalRequest request) {
        return toDomain(jpaRepository.saveAndFlush(toEntity(request)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WithdrawalRequest> findById(Long id) {
        return jpaRepository.findById(id).map(WithdrawalRequestRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional
    public Optional<WithdrawalRequest> findByIdForUpdate(Long id) {
        return jpaRepository.findByIdWithLock(id).map(WithdrawalRequestRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WithdrawalRequest> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(WithdrawalRequestRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WithdrawalRequest> findByUserIdNewestFirst(Long userId) {
        return jpaRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(WithdrawalRequestRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WithdrawalRequest> findAllNewestFirst(WithdrawalRequestStatus status) {
        List<JpaWithdrawalRequestEntity> rows = status == null
                ? jpaRepository.findAllByOrderByCreatedAtDescIdDesc()
                : jpaRepository.findByStatusOrderByCreatedAtDescIdDesc(status);
        return rows.stream().map(WithdrawalRequestRepositoryAdapter::toDomain).toList();
    }

    private static JpaWithdrawalRequestEntity toEntity(WithdrawalRequest d) {
        return new JpaWithdrawalRequestEntity(d.getId(), d.getUserId(), d.getAmount(), d.getStatus(),
                d.getIdempotencyKey(), d.getCreatedAt(), d.getProcessingStartedAt(), d.getProcessingStartedBy(),
                d.getProcessedAt(), d.getProcessedBy(), d.getRejectionReason(), d.getVersion());
    }

    private static WithdrawalRequest toDomain(JpaWithdrawalRequestEntity e) {
        return new WithdrawalRequest(e.getId(), e.getUserId(), e.getAmount(), e.getStatus(),
                e.getIdempotencyKey(), e.getCreatedAt(), e.getProcessingStartedAt(), e.getProcessingStartedBy(),
                e.getProcessedAt(), e.getProcessedBy(), e.getRejectionReason(), e.getVersion());
    }
}
