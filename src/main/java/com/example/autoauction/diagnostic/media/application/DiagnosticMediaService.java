package com.example.autoauction.diagnostic.media.application;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.MediaType;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import com.example.autoauction.diagnostic.media.web.dto.MediaIntentResponse;
import com.example.autoauction.diagnostic.media.web.dto.MediaReadUrlResponse;
import com.example.autoauction.diagnostic.media.web.dto.MediaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MEDIA-3.2. The core invariant enforced throughout this class: a
 * DiagnosticMedia record is never trusted merely because the client claims
 * it belongs to a diagnostic request. Every write and every read
 * independently re-derives: authenticated caller + current diagnostic
 * assignment + current request state + persisted request.templateId + a
 * valid InspectionItem belonging to that exact template + persisted media
 * uploader ownership + (for finalize) actual object existence and
 * server-side content validation - never client-supplied values.
 */
@Slf4j
@Service
public class DiagnosticMediaService {

    private static final Map<String, String> EXTENSION_BY_MIME = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "video/mp4", ".mp4",
            "video/webm", ".webm"
    );
    private static final Set<String> PHOTO_MIME_TYPES = Set.of("image/jpeg", "image/png");
    private static final Set<String> VIDEO_MIME_TYPES = Set.of("video/mp4", "video/webm");

    private final DiagnosticMediaRepository mediaRepository;
    private final DiagnosticRequestRepository requestRepository;
    private final InspectionTemplateRepository templateRepository;
    private final StorageClient storageClient;

    private final long maxSizeBytes;
    private final Duration uploadTtl;
    private final Duration readUrlTtl;

    public DiagnosticMediaService(
            DiagnosticMediaRepository mediaRepository,
            DiagnosticRequestRepository requestRepository,
            InspectionTemplateRepository templateRepository,
            StorageClient storageClient,
            @Value("${diagnostic.media.max-size-bytes}") long maxSizeBytes,
            @Value("${diagnostic.media.upload-ttl-minutes}") long uploadTtlMinutes,
            @Value("${diagnostic.media.read-url-ttl-minutes}") long readUrlTtlMinutes) {
        this.mediaRepository = mediaRepository;
        this.requestRepository = requestRepository;
        this.templateRepository = templateRepository;
        this.storageClient = storageClient;
        this.maxSizeBytes = maxSizeBytes;
        this.uploadTtl = Duration.ofMinutes(uploadTtlMinutes);
        this.readUrlTtl = Duration.ofMinutes(readUrlTtlMinutes);
    }

    // ==================== Intent ====================

    public MediaIntentResponse createIntent(Long requestId, Long itemId, Long diagnosticId,
                                             MediaType mediaType, String declaredMimeType, long declaredSizeBytes) {
        DiagnosticRequest request = loadRequestAssignedTo(requestId, diagnosticId);
        InspectionItem item = loadItemBoundToRequestTemplate(request, itemId);

        if (mediaType == MediaType.PHOTO && !item.isHasPhoto()) {
            throw new IllegalArgumentException("Этот пункт анкеты не поддерживает фото");
        }
        if (mediaType == MediaType.VIDEO && !item.isHasVideo()) {
            throw new IllegalArgumentException("Этот пункт анкеты не поддерживает видео");
        }

        Set<String> allowedForType = mediaType == MediaType.PHOTO ? PHOTO_MIME_TYPES : VIDEO_MIME_TYPES;
        if (declaredMimeType == null || !allowedForType.contains(declaredMimeType)) {
            throw new IllegalArgumentException("Недопустимый MIME-тип для " + mediaType + ": " + declaredMimeType);
        }
        if (declaredSizeBytes <= 0 || declaredSizeBytes > maxSizeBytes) {
            throw new IllegalArgumentException("Размер файла превышает допустимый лимит (" + maxSizeBytes + " байт)");
        }

        // Server-generated, collision-resistant, never derived from client input
        // (filename/VIN/path). The client never chooses or supplies this.
        String storageKey = "diagnostic-media/" + requestId + "/" + UUID.randomUUID()
                + EXTENSION_BY_MIME.getOrDefault(declaredMimeType, "");

        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(requestId);
        media.setInspectionItemId(itemId);
        media.setUploaderId(diagnosticId);
        media.setStorageKey(storageKey);
        media.setMediaType(mediaType);
        media.setMimeType(declaredMimeType); // provisional - overwritten with the server-verified value at finalize
        media.setSizeBytes(declaredSizeBytes); // provisional - same
        media.setSortOrder(0);
        media.setUploadStatus(UploadStatus.PENDING_UPLOAD);
        media.setProcessingStatus(mediaType == MediaType.PHOTO ? ProcessingStatus.NOT_REQUIRED : ProcessingStatus.PENDING);

        DiagnosticMedia saved = mediaRepository.save(media);
        StorageClient.PresignedUpload presigned = storageClient.createPresignedUpload(storageKey, declaredMimeType, uploadTtl);

        log.info("Diagnostic media intent created: id={}, request={}, item={}, type={}",
                saved.getId(), requestId, itemId, mediaType);

        return new MediaIntentResponse(saved.getId(), presigned.uploadUrl().toString(), presigned.expiresAt(),
                mediaType, UploadStatus.PENDING_UPLOAD);
    }

    // ==================== Finalize ====================

    public MediaResponse finalize(Long mediaId, Long diagnosticId) {
        DiagnosticMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Медиафайл не найден: " + mediaId));

        // SEC-14: who is asking is checked BEFORE the idempotent answer below - it used to come
        // after it, so any authenticated user could "finalize" someone else's READY media and read
        // its metadata. Only identity here (the request's assigned diagnostician who uploaded the
        // file), so the uploader's late retry keeps its idempotent answer whatever the request status.
        DiagnosticRequest owning = requestRepository.findById(media.getDiagnosticRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + media.getDiagnosticRequestId()));
        if (owning.getAssignedTo() == null || !owning.getAssignedTo().equals(diagnosticId)) {
            throw new SecurityException("Вы не назначены на эту заявку");
        }
        if (!media.getUploaderId().equals(diagnosticId)) {
            throw new SecurityException("Вы не загружали этот файл");
        }

        // Idempotent: a media item already READY (or terminally FAILED/EXPIRED/DELETED) is not
        // re-validated - repeated finalize calls must never duplicate work or mutate a settled row.
        if (media.getUploadStatus() != UploadStatus.PENDING_UPLOAD) {
            if (media.getUploadStatus() == UploadStatus.READY) {
                return MediaResponse.fromDomain(media);
            }
            throw new IllegalStateException("Медиафайл не в статусе PENDING_UPLOAD: " + media.getUploadStatus());
        }

        // Full, live re-authorization - never trust the authorization performed at intent time.
        DiagnosticRequest request = loadRequestAssignedTo(media.getDiagnosticRequestId(), diagnosticId);
        loadItemBoundToRequestTemplate(request, media.getInspectionItemId());

        // R2 I/O happens here, deliberately outside any DB transaction (each repository call
        // above/below is its own short, independently-committing operation).
        var headResult = storageClient.headObject(media.getStorageKey());
        if (headResult.isEmpty()) {
            mediaRepository.markFailed(mediaId);
            throw new IllegalStateException("Объект не найден в хранилище");
        }
        long actualSizeBytes = headResult.get().sizeBytes();
        if (actualSizeBytes <= 0 || actualSizeBytes > maxSizeBytes) {
            mediaRepository.markFailed(mediaId);
            throw new IllegalStateException("Фактический размер объекта превышает допустимый лимит");
        }

        byte[] leadingBytes = storageClient.fetchLeadingBytes(media.getStorageKey(), MediaContentValidator.PROBE_LENGTH)
                .orElse(new byte[0]);
        String detectedMimeType = MediaContentValidator.detectMimeType(leadingBytes);
        if (detectedMimeType == null) {
            mediaRepository.markFailed(mediaId);
            throw new IllegalStateException("Содержимое файла не распознано как поддерживаемый тип медиа");
        }
        Set<String> allowedForType = media.getMediaType() == MediaType.PHOTO ? PHOTO_MIME_TYPES : VIDEO_MIME_TYPES;
        if (!allowedForType.contains(detectedMimeType)) {
            mediaRepository.markFailed(mediaId);
            throw new IllegalStateException("Фактическое содержимое файла не соответствует заявленному типу медиа");
        }

        boolean applied = mediaRepository.finalizeReady(mediaId, detectedMimeType, actualSizeBytes);
        DiagnosticMedia current = mediaRepository.findById(mediaId).orElseThrow();
        if (!applied && current.getUploadStatus() != UploadStatus.READY) {
            throw new IllegalStateException("Не удалось завершить загрузку: заявка более не в допустимом состоянии");
        }

        log.info("Diagnostic media {} finalized: mimeType={}, sizeBytes={}", mediaId, detectedMimeType, actualSizeBytes);
        return MediaResponse.fromDomain(current);
    }

    // ==================== Read URL ====================

    public MediaReadUrlResponse getReadUrl(Long mediaId, Long callerUserId, boolean isAdmin) {
        return signedReadUrl(loadAuthorizedMedia(mediaId, callerUserId, isAdmin));
    }

    /**
     * MEDIA-3.4 Phase 11: same authorization boundary as getReadUrl() (owner/
     * assigned diagnostician/admin), presigning thumbnailStorageKey instead
     * of storageKey. Deliberately reuses loadAuthorizedMedia() rather than
     * re-deriving the owner/diagnostician/admin check - a second,
     * independently-written authorization algorithm here would risk drifting
     * from getReadUrl()'s boundary.
     */
    public MediaReadUrlResponse getThumbnailReadUrl(Long mediaId, Long callerUserId, boolean isAdmin) {
        return signedThumbnailReadUrl(loadAuthorizedMedia(mediaId, callerUserId, isAdmin));
    }

    /** Public (unauthenticated) access - the request MUST be APPROVED. Called only from PublicReportController. */
    public MediaReadUrlResponse getReadUrlForApprovedReport(Long mediaId) {
        return signedReadUrl(loadApprovedReportMedia(mediaId));
    }

    /**
     * MEDIA-3.4 Phase 11: validates the exact same not-found/not-approved
     * boundary as getReadUrlForApprovedReport() (via loadApprovedReportMedia)
     * PLUS the same uploadStatus=READY check signedReadUrl()/
     * signedThumbnailReadUrl() already apply - checked here too (not just
     * inside signedThumbnailReadUrl) so PublicReportController can catch
     * exactly this "media not visible at all" boundary and keep mapping it
     * to its existing blanket 404, while a genuinely-visible-but-not-yet-
     * processed thumbnail (thumbnailStorageKey == null) is left to surface
     * as its own distinct 409 via thumbnailReadUrlFor() below - deliberately
     * NOT collapsed into the same 404 the original-media endpoint uses for
     * "not found"/"not approved", since it leaks no information a public,
     * already-APPROVED-report caller doesn't already have.
     */
    public DiagnosticMedia loadVisibleApprovedReportMedia(Long mediaId) {
        DiagnosticMedia media = loadApprovedReportMedia(mediaId);
        if (media.getUploadStatus() != UploadStatus.READY) {
            throw new IllegalStateException("Медиафайл недоступен для чтения: " + media.getUploadStatus());
        }
        return media;
    }

    /** Presigns the thumbnail of a media already validated by loadVisibleApprovedReportMedia(). */
    public MediaReadUrlResponse thumbnailReadUrlFor(DiagnosticMedia media) {
        return signedThumbnailReadUrl(media);
    }

    private DiagnosticMedia loadAuthorizedMedia(Long mediaId, Long callerUserId, boolean isAdmin) {
        DiagnosticMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Медиафайл не найден: " + mediaId));
        DiagnosticRequest request = requestRepository.findById(media.getDiagnosticRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + media.getDiagnosticRequestId()));

        boolean isOwner = request.getUserId().equals(callerUserId);
        boolean isAssignedDiagnostician = callerUserId.equals(request.getAssignedTo());
        if (!isAdmin && !isOwner && !isAssignedDiagnostician) {
            throw new SecurityException("Нет доступа к этому медиафайлу");
        }
        return media;
    }

    private DiagnosticMedia loadApprovedReportMedia(Long mediaId) {
        DiagnosticMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Медиафайл не найден: " + mediaId));
        DiagnosticRequest request = requestRepository.findById(media.getDiagnosticRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + media.getDiagnosticRequestId()));

        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.APPROVED) {
            throw new IllegalStateException("Отчёт не одобрен");
        }
        return media;
    }

    private MediaReadUrlResponse signedReadUrl(DiagnosticMedia media) {
        if (media.getUploadStatus() != UploadStatus.READY) {
            throw new IllegalStateException("Медиафайл недоступен для чтения: " + media.getUploadStatus());
        }
        URI url = storageClient.createPresignedReadUrl(media.getStorageKey(), readUrlTtl);
        return new MediaReadUrlResponse(url.toString(), java.time.Instant.now().plus(readUrlTtl));
    }

    /**
     * MEDIA-3.4 Phase 11: mirrors signedReadUrl() exactly except for which
     * storage key is presigned. Re-checks uploadStatus=READY independently
     * (not just relying on a caller having already checked it) so this
     * method is safe to call directly, matching signedReadUrl()'s own
     * defensive shape.
     */
    private MediaReadUrlResponse signedThumbnailReadUrl(DiagnosticMedia media) {
        if (media.getUploadStatus() != UploadStatus.READY) {
            throw new IllegalStateException("Медиафайл недоступен для чтения: " + media.getUploadStatus());
        }
        if (media.getThumbnailStorageKey() == null) {
            throw new IllegalStateException("Превью недоступно для медиафайла: " + media.getId());
        }
        URI url = storageClient.createPresignedReadUrl(media.getThumbnailStorageKey(), readUrlTtl);
        return new MediaReadUrlResponse(url.toString(), java.time.Instant.now().plus(readUrlTtl));
    }

    // ==================== Delete ====================

    public void delete(Long mediaId, Long callerUserId, boolean isAdmin) {
        DiagnosticMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Медиафайл не найден: " + mediaId));
        DiagnosticRequest request = requestRepository.findById(media.getDiagnosticRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + media.getDiagnosticRequestId()));

        if (!isAdmin) {
            boolean isAssignedDiagnostician = callerUserId.equals(request.getAssignedTo());
            if (!isAssignedDiagnostician) {
                throw new SecurityException("Нет доступа к этому медиафайлу");
            }
            if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS) {
                throw new IllegalStateException("Удаление доступно только пока заявка в статусе IN_PROGRESS");
            }
        }

        // Idempotent by construction: a second call finds uploadStatus already DELETED and
        // softDelete()'s WHERE guard simply matches nothing - safe no-op, not an error.
        mediaRepository.softDelete(mediaId);
    }

    // ==================== List / Caption / Order ====================

    public List<MediaResponse> listForItem(Long requestId, Long itemId, Long callerUserId, boolean isAdmin) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));

        boolean isOwner = request.getUserId().equals(callerUserId);
        boolean isAssignedDiagnostician = callerUserId.equals(request.getAssignedTo());
        if (!isAdmin && !isOwner && !isAssignedDiagnostician) {
            throw new SecurityException("Нет доступа к этой заявке");
        }

        return mediaRepository.findByRequestAndItem(requestId, itemId).stream()
                .filter(m -> m.getUploadStatus() != UploadStatus.DELETED)
                .map(MediaResponse::fromDomain)
                .collect(Collectors.toList());
    }

    public MediaResponse updateCaption(Long mediaId, Long diagnosticId, String caption) {
        DiagnosticMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Медиафайл не найден: " + mediaId));
        DiagnosticRequest request = loadRequestAssignedTo(media.getDiagnosticRequestId(), diagnosticId);
        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS) {
            throw new IllegalStateException("Изменение доступно только пока заявка в статусе IN_PROGRESS");
        }
        media.setCaption(caption);
        return MediaResponse.fromDomain(mediaRepository.save(media));
    }

    public void reorder(Long requestId, Long itemId, Long diagnosticId, List<Long> orderedMediaIds) {
        DiagnosticRequest request = loadRequestAssignedTo(requestId, diagnosticId);
        loadItemBoundToRequestTemplate(request, itemId);

        if (new HashSet<>(orderedMediaIds).size() != orderedMediaIds.size()) {
            throw new IllegalArgumentException("Список ID медиафайлов содержит дубликаты");
        }

        List<DiagnosticMedia> existing = mediaRepository.findByRequestAndItem(requestId, itemId).stream()
                .filter(m -> m.getUploadStatus() != UploadStatus.DELETED)
                .toList();
        Set<Long> existingIds = existing.stream().map(DiagnosticMedia::getId).collect(Collectors.toSet());
        Set<Long> suppliedIds = new HashSet<>(orderedMediaIds);

        if (!existingIds.equals(suppliedIds)) {
            throw new IllegalArgumentException(
                    "Список ID должен содержать в точности все медиафайлы этого пункта анкеты, не более и не менее");
        }

        mediaRepository.replaceOrdering(orderedMediaIds);
    }

    // ==================== Shared authorization/resolution helpers ====================

    private DiagnosticRequest loadRequestAssignedTo(Long requestId, Long diagnosticId) {
        DiagnosticRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + requestId));
        if (request.getAssignedTo() == null || !request.getAssignedTo().equals(diagnosticId)) {
            throw new SecurityException("Вы не назначены на эту заявку");
        }
        if (request.getStatus() != DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS) {
            throw new IllegalStateException("Заявка должна быть в статусе IN_PROGRESS. Текущий статус: " + request.getStatus());
        }
        if (request.getTemplateId() == null) {
            throw new IllegalStateException("Для этой заявки ещё не выбран шаблон анкеты");
        }
        return request;
    }

    /** Verifies item exists AND belongs to request.templateId - never trusts the caller's claim. */
    private InspectionItem loadItemBoundToRequestTemplate(DiagnosticRequest request, Long itemId) {
        InspectionItem item = templateRepository.findItemById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Пункт анкеты не найден: " + itemId));
        InspectionGroup group = templateRepository.findGroupById(item.getGroupId())
                .orElseThrow(() -> new IllegalArgumentException("Группа анкеты не найдена: " + item.getGroupId()));
        if (!group.getTemplateId().equals(request.getTemplateId())) {
            throw new IllegalArgumentException("Пункт анкеты не принадлежит шаблону, привязанному к заявке");
        }
        return item;
    }
}
