// diagnostic/commitment/infrastructure/persistence/SellerDiagnosticCommitmentMapper.java
package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import org.springframework.stereotype.Component;

@Component
public class SellerDiagnosticCommitmentMapper {

    public JpaSellerDiagnosticCommitmentEntity toEntity(SellerDiagnosticCommitment domain) {
        if (domain == null) return null;
        JpaSellerDiagnosticCommitmentEntity entity = new JpaSellerDiagnosticCommitmentEntity(
                domain.getDiagnosticRequestId(),
                domain.getDepositId(),
                domain.getCommittedAmount(),
                domain.getFrozenAt()
        );
        entity.setId(domain.getId());
        return entity;
    }

    public SellerDiagnosticCommitment toDomain(JpaSellerDiagnosticCommitmentEntity entity) {
        if (entity == null) return null;
        SellerDiagnosticCommitment domain = new SellerDiagnosticCommitment();
        domain.setId(entity.getId());
        domain.setDiagnosticRequestId(entity.getDiagnosticRequestId());
        domain.setDepositId(entity.getDepositId());
        domain.setCommittedAmount(entity.getCommittedAmount());
        domain.setFrozenAt(entity.getFrozenAt());
        return domain;
    }
}
