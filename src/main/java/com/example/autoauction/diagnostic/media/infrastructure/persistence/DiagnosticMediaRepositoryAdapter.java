package com.example.autoauction.diagnostic.media.infrastructure.persistence;

import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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

    // ==================== MEDIA-3.4: video processing ====================

    @Override
    public boolean claimForProcessing(Long id) {
        int updated = jpaRepository.claimForProcessing(id, OffsetDateTime.now());
        if (updated > 0) {
            log.info("DiagnosticMedia {} claimed for processing (PENDING -> PROCESSING)", id);
            return true;
        }
        return false;
    }

    @Override
    public boolean finalizeProcessingReady(Long id, Integer durationSeconds, Integer width, Integer height, String thumbnailStorageKey) {
        int updated = jpaRepository.finalizeProcessingReady(id, durationSeconds, width, height, thumbnailStorageKey, OffsetDateTime.now());
        if (updated > 0) {
            log.info("DiagnosticMedia {} processing finalized READY (duration={}, {}x{}, thumbnail={})",
                    id, durationSeconds, width, height, thumbnailStorageKey);
            return true;
        }
        return false;
    }

    @Override
    public boolean markProcessingFailed(Long id) {
        int updated = jpaRepository.markProcessingFailed(id, OffsetDateTime.now());
        if (updated > 0) {
            log.warn("DiagnosticMedia {} processing marked FAILED", id);
            return true;
        }
        return false;
    }

    @Override
    public boolean resetStaleProcessing(Long id) {
        int updated = jpaRepository.resetStaleProcessing(id, OffsetDateTime.now());
        if (updated > 0) {
            log.warn("DiagnosticMedia {} stale PROCESSING reset to PENDING", id);
            return true;
        }
        return false;
    }

    @Override
    public boolean retryFailedProcessing(Long id) {
        int updated = jpaRepository.retryFailedProcessing(id, OffsetDateTime.now());
        if (updated > 0) {
            log.info("DiagnosticMedia {} processing retry requested (FAILED -> PENDING)", id);
            return true;
        }
        return false;
    }

    @Override
    public List<DiagnosticMedia> findPendingProcessing(int limit) {
        return jpaRepository.findByUploadStatusAndProcessingStatusOrderByCreatedAtAsc(
                        UploadStatus.READY, ProcessingStatus.PENDING, PageRequest.of(0, limit))
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<DiagnosticMedia> findStaleProcessing(OffsetDateTime cutoff, int limit) {
        return jpaRepository.findByProcessingStatusAndProcessingClaimedAtBeforeOrderByProcessingClaimedAtAsc(
                        ProcessingStatus.PROCESSING, cutoff, PageRequest.of(0, limit))
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    // ==================== MEDIA-HARDENING-1: recoverable thumbnail orphans ====================

    @Override
    public boolean declarePendingThumbnail(Long id, String thumbnailStorageKey) {
        int updated = jpaRepository.declarePendingThumbnail(id, thumbnailStorageKey, OffsetDateTime.now());
        if (updated > 0) {
            log.info("DiagnosticMedia {} declared pending thumbnail (key={})", id, thumbnailStorageKey);
            return true;
        }
        return false;
    }

    @Override
    public boolean clearPendingThumbnail(Long id, String expectedKey) {
        int updated = jpaRepository.clearPendingThumbnail(id, expectedKey, OffsetDateTime.now());
        if (updated > 0) {
            log.info("DiagnosticMedia {} pending thumbnail marker cleared (key={})", id, expectedKey);
            return true;
        }
        return false;
    }

    @Override
    public List<DiagnosticMedia> findPendingThumbnailOrphans(int limit) {
        return jpaRepository.findByPendingThumbnailStorageKeyIsNotNullAndProcessingStatusNot(
                        ProcessingStatus.PROCESSING, PageRequest.of(0, limit))
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }
}
