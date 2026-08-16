// common/infrastructure/persistence/JpaFailedOperationRepositoryAdapter.java
package com.example.autoauction.common.infrastructure.persistence;

import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@Transactional
public class JpaFailedOperationRepositoryAdapter implements FailedOperationRepository {

    private final SpringDataFailedOperationJpaRepository jpaRepository;

    public JpaFailedOperationRepositoryAdapter(SpringDataFailedOperationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public FailedOperation save(FailedOperation failedOperation) {
        JpaFailedOperationEntity entity = toEntity(failedOperation);
        JpaFailedOperationEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<FailedOperation> findById(Long id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<FailedOperation> findByStatus(String status) {
        return jpaRepository.findByStatus(status).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<FailedOperation> findAll() {
        return jpaRepository.findAll().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public List<FailedOperation> findRetryablePending(OffsetDateTime now) {
        return jpaRepository.findRetryablePending(FailedOperation.STATUS_PENDING, now).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean claim(Long id) {
        int updated = jpaRepository.claim(id, FailedOperation.STATUS_PENDING, FailedOperation.STATUS_PROCESSING,
                OffsetDateTime.now());
        return updated > 0;
    }

    private JpaFailedOperationEntity toEntity(FailedOperation domain) {
        if (domain == null) return null;
        // R-2: previously this constructed a brand-new entity and never carried the id
        // across, so save() always INSERTed a new row and could never update an existing
        // one - there was no way to persist a status/retryCount transition at all. Now
        // carries the full current state so an existing id performs a genuine UPDATE.
        JpaFailedOperationEntity entity = new JpaFailedOperationEntity(
                domain.getOperationType(),
                domain.getUserId(),
                domain.getAuctionId(),
                domain.getErrorMessage(),
                domain.getStackTrace(),
                domain.getCreatedAt()
        );
        entity.setId(domain.getId());
        entity.setRetryCount(domain.getRetryCount());
        entity.setStatus(domain.getStatus());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setNextRetryAt(domain.getNextRetryAt());
        entity.setResolvedAt(domain.getResolvedAt());
        return entity;
    }

    private FailedOperation toDomain(JpaFailedOperationEntity entity) {
        if (entity == null) return null;
        FailedOperation operation = new FailedOperation(
                entity.getOperationType(),
                entity.getUserId(),
                entity.getAuctionId(),
                entity.getErrorMessage(),
                entity.getStackTrace(),
                entity.getCreatedAt()
        );
        operation.setId(entity.getId());
        operation.setRetryCount(entity.getRetryCount());
        operation.setStatus(entity.getStatus());
        operation.setUpdatedAt(entity.getUpdatedAt());
        operation.setNextRetryAt(entity.getNextRetryAt());
        operation.setResolvedAt(entity.getResolvedAt());
        return operation;
    }
}