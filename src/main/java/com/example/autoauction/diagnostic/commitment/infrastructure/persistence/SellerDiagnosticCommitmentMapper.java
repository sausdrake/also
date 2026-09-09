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
                domain.getDiagnosticFeeAmount(),
                domain.getStatus(),
                domain.getFrozenAt()
        );
        entity.setId(domain.getId());
        entity.setFinalizationOutcome(domain.getFinalizationOutcome());
        entity.setFinalizedAt(domain.getFinalizedAt());
        entity.setPenaltyAmount(domain.getPenaltyAmount());
        entity.setRefundAmount(domain.getRefundAmount());
        entity.setPolicyVersionId(domain.getPolicyVersionId());
        return entity;
    }

    public SellerDiagnosticCommitment toDomain(JpaSellerDiagnosticCommitmentEntity entity) {
        if (entity == null) return null;
        SellerDiagnosticCommitment domain = new SellerDiagnosticCommitment();
        domain.setId(entity.getId());
        domain.setDiagnosticRequestId(entity.getDiagnosticRequestId());
        domain.setDepositId(entity.getDepositId());
        domain.setCommittedAmount(entity.getCommittedAmount());
        domain.setDiagnosticFeeAmount(entity.getDiagnosticFeeAmount());
        domain.setStatus(entity.getStatus());
        domain.setFrozenAt(entity.getFrozenAt());
        domain.setFinalizationOutcome(entity.getFinalizationOutcome());
        domain.setFinalizedAt(entity.getFinalizedAt());
        domain.setPenaltyAmount(entity.getPenaltyAmount());
        domain.setRefundAmount(entity.getRefundAmount());
        domain.setPolicyVersionId(entity.getPolicyVersionId());
        return domain;
    }
}
