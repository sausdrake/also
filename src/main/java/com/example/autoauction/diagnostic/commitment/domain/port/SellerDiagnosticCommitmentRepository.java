package com.example.autoauction.diagnostic.commitment.domain.port;

import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;

import java.util.Optional;

public interface SellerDiagnosticCommitmentRepository {

    SellerDiagnosticCommitment save(SellerDiagnosticCommitment commitment);

    Optional<SellerDiagnosticCommitment> findByDiagnosticRequestId(Long diagnosticRequestId);

    boolean existsByDiagnosticRequestId(Long diagnosticRequestId);

    void deleteByDiagnosticRequestId(Long diagnosticRequestId);
}
