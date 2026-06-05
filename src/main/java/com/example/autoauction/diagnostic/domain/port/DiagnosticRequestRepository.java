package com.example.autoauction.diagnostic.domain.port;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

public interface DiagnosticRequestRepository {

    DiagnosticRequest save(DiagnosticRequest request);

    Optional<DiagnosticRequest> findById(Long id);

    Page<DiagnosticRequest> findByUserId(Long userId, Pageable pageable);

    Page<DiagnosticRequest> findByStatus(DiagnosticRequest.DiagnosticRequestStatus status, Pageable pageable);

    Page<DiagnosticRequest> findByAssignedTo(Long diagnosticId, Pageable pageable);

    List<DiagnosticRequest> findPendingRequests();

    Optional<DiagnosticRequest> findByIdAndUserId(Long id, Long userId);

    boolean assignToDiagnostic(Long requestId, Long diagnosticId);

    boolean markAsCompleted(Long requestId, Long diagnosticId);

    Page<DiagnosticRequest> findAll(Pageable pageable);

    boolean resetForRework(Long requestId, Long diagnosticId);
}