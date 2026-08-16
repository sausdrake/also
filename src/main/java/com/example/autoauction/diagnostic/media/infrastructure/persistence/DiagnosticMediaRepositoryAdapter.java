package com.example.autoauction.diagnostic.media.infrastructure.persistence;

import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class DiagnosticMediaRepositoryAdapter implements DiagnosticMediaRepository {

    private final SpringDataDiagnosticMediaJpaRepository jpaRepository;
    private final DiagnosticMediaMapper mapper;

    @Override
    public DiagnosticMedia save(DiagnosticMedia media) {
        JpaDiagnosticMediaEntity entity = mapper.toEntity(media);
        JpaDiagnosticMediaEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DiagnosticMedia> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagnosticMedia> findByRequestAndItem(Long diagnosticRequestId, Long inspectionItemId) {
        return jpaRepository.findByDiagnosticRequestIdAndInspectionItemIdOrderBySortOrderAsc(diagnosticRequestId, inspectionItemId)
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagnosticMedia> findByRequest(Long diagnosticRequestId) {
        return jpaRepository.findByDiagnosticRequestId(diagnosticRequestId)
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagnosticMedia> findPendingUploadOlderThan(OffsetDateTime cutoff) {
        return jpaRepository.findByUploadStatusAndCreatedAtBefore(UploadStatus.PENDING_UPLOAD, cutoff)
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagnosticMedia> findByUploadStatus(UploadStatus status) {
        return jpaRepository.findByUploadStatus(status)
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public boolean updateUploadStatusIfCurrent(Long id, UploadStatus fromStatus, UploadStatus toStatus) {
        int updated = jpaRepository.updateUploadStatusIfCurrent(id, fromStatus, toStatus, OffsetDateTime.now());
        if (updated > 0) {
            log.info("DiagnosticMedia {} transitioned {} -> {}", id, fromStatus, toStatus);
            return true;
        }
        return false;
    }

    @Override
    public boolean finalizeReady(Long id, String verifiedMimeType, long verifiedSizeBytes) {
        int updated = jpaRepository.finalizeReady(id, verifiedMimeType, verifiedSizeBytes, OffsetDateTime.now());
        if (updated > 0) {
            log.info("DiagnosticMedia {} finalized READY (mimeType={}, sizeBytes={})", id, verifiedMimeType, verifiedSizeBytes);
            return true;
        }
        return false;
    }

    @Override
    public boolean markFailed(Long id) {
        int updated = jpaRepository.markFailed(id, OffsetDateTime.now());
        if (updated > 0) {
            log.warn("DiagnosticMedia {} marked FAILED", id);
            return true;
        }
        return false;
    }

    @Override
    public boolean softDelete(Long id) {
        int updated = jpaRepository.softDelete(id, OffsetDateTime.now());
        if (updated > 0) {
            log.info("DiagnosticMedia {} soft-deleted", id);
            return true;
        }
        return false;
    }

    @Override
    public void replaceOrdering(List<Long> orderedMediaIds) {
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < orderedMediaIds.size(); i++) {
            jpaRepository.updateSortOrder(orderedMediaIds.get(i), i, now);
        }
    }
}
