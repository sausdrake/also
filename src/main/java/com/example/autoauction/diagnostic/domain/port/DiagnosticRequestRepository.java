package com.example.autoauction.diagnostic.domain.port;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Collection;
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

    /** Atomic conditional bind; see SpringDataDiagnosticRequestJpaRepository.bindTemplate. */
    boolean bindTemplate(Long requestId, Long diagnosticId, Long templateId);

    /** MEDIA-3.1B historical backfill; see SpringDataDiagnosticRequestJpaRepository.bindTemplateIfUnset. */
    boolean bindTemplateIfUnset(Long requestId, Long templateId);

    /** All requests, used only by the MEDIA-3.1B historical backfill. */
    List<DiagnosticRequest> findAll();

    boolean markAsCompleted(Long requestId, Long diagnosticId);

    Page<DiagnosticRequest> findAll(Pageable pageable);

    boolean resetForRework(Long requestId, Long diagnosticId);

    Optional<DiagnosticRequest> findApprovedByVin(String vin);

    /** True if the user has any request in one of the given statuses (order-independent). */
    boolean existsByUserIdAndStatusIn(Long userId, Collection<DiagnosticRequest.DiagnosticRequestStatus> statuses);

    /** True if any request for this VIN is in one of the given statuses. */
    boolean existsByVinAndStatusIn(String vin, Collection<DiagnosticRequest.DiagnosticRequestStatus> statuses);

    /** MVP-005: whether a re-diagnostic request already supersedes this request. */
    boolean existsByPreviousDiagnosticRequestId(Long previousDiagnosticRequestId);

    /** MVP-005: the most recent re-diagnostic request of a vehicle, if any. */
    Optional<DiagnosticRequest> findLatestRediagnosticOfVehicle(Long vehicleId);

    /** MVP-005: every diagnostic request ever made for a VIN, oldest first (diagnostic history). */
    List<DiagnosticRequest> findByVinOrderByCreatedAtAsc(String vin);
}