// common/infrastructure/persistence/JpaFailedOperationRepositoryAdapter.java
package com.example.autoauction.common.infrastructure.persistence;

import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }

    private JpaFailedOperationEntity toEntity(FailedOperation domain) {
        if (domain == null) return null;
        return new JpaFailedOperationEntity(
                domain.getOperationType(),
                domain.getUserId(),
                domain.getAuctionId(),
                domain.getErrorMessage(),
                domain.getStackTrace(),
                domain.getCreatedAt()
        );
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
        return operation;
    }
}