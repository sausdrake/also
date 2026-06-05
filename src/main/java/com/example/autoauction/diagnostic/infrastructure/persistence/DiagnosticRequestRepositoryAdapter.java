package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional
public class DiagnosticRequestRepositoryAdapter implements DiagnosticRequestRepository {

    private final SpringDataDiagnosticRequestJpaRepository jpaRepository;
    private final DiagnosticRequestMapper mapper;

    @Override
    public DiagnosticRequest save(DiagnosticRequest request) {
        JpaDiagnosticRequestEntity entity = mapper.toEntity(request);
        JpaDiagnosticRequestEntity saved = jpaRepository.save(entity);
        log.info("Saved diagnostic request: id={}, userId={}, vin={}",
                saved.getId(), saved.getUserId(), saved.getVin());
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DiagnosticRequest> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DiagnosticRequest> findByUserId(Long userId, Pageable pageable) {
        return jpaRepository.findByUserId(userId, pageable).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DiagnosticRequest> findByStatus(DiagnosticRequest.DiagnosticRequestStatus status, Pageable pageable) {
        return jpaRepository.findByStatus(status, pageable).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DiagnosticRequest> findByAssignedTo(Long diagnosticId, Pageable pageable) {
        return jpaRepository.findByAssignedTo(diagnosticId, pageable).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagnosticRequest> findPendingRequests() {
        return jpaRepository.findByStatusAndAssignedToIsNull(DiagnosticRequest.DiagnosticRequestStatus.PENDING)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DiagnosticRequest> findByIdAndUserId(Long id, Long userId) {
        return jpaRepository.findByIdAndUserId(id, userId).map(mapper::toDomain);
    }

    @Override
    public boolean assignToDiagnostic(Long requestId, Long diagnosticId) {
        int updated = jpaRepository.assignToDiagnostic(requestId, diagnosticId);
        if (updated > 0) {
            log.info("Assigned request {} to diagnostic {}", requestId, diagnosticId);
            return true;
        }
        log.warn("Failed to assign request {} to diagnostic {}", requestId, diagnosticId);
        return false;
    }

    @Override
    public boolean markAsCompleted(Long requestId, Long diagnosticId) {
        int updated = jpaRepository.markAsCompleted(requestId, diagnosticId, OffsetDateTime.now());
        if (updated > 0) {
            log.info("Marked request {} as completed by diagnostic {}", requestId, diagnosticId);
            return true;
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DiagnosticRequest> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable).map(mapper::toDomain);
    }

    @Override
    public boolean resetForRework(Long requestId, Long diagnosticId) {
        int updated = jpaRepository.resetForRework(requestId, diagnosticId, OffsetDateTime.now());
        if (updated > 0) {
            log.info("Reset request {} for rework by diagnostic {}", requestId, diagnosticId);
            return true;
        }
        log.warn("Failed to reset request {} for rework (not REJECTED or not assigned to {})", requestId, diagnosticId);
        return false;
    }
}