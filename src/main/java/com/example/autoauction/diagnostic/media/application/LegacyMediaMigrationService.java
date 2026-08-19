package com.example.autoauction.diagnostic.media.application;

import com.example.autoauction.common.media.domain.port.MediaAssetRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionAnswerRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.MediaType;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import com.example.autoauction.diagnostic.media.web.dto.LegacyMediaMigrationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MEDIA-3.3: one-off, idempotent, admin-triggered migration of historical
 * InspectionAnswer.photoUrls/videoUrls into canonical DiagnosticMedia/R2.
 * Independent of MediaReconciliationService/AuctionCompletionScheduler/
 * FinancialRecoveryScheduler - owns its own work, no scheduler.
 *
 * SCOPE DECISION (not explicitly stated in the originating prompt, made here
 * and reported explicitly): only requests in a STABLE status - COMPLETED or
 * APPROVED (see STABLE_STATUSES) - are migrated.
 *
 * REJECTED IS DELIBERATELY EXCLUDED, not merely "not yet included". REJECTED
 * is not terminal: DiagnosticRequestService.resetForRework() (only valid from
 * REJECTED) legitimately transitions it back to IN_PROGRESS, at which point
 * saveInspectionAnswers() (MEDIA-3.3 Phase 1 fix) allows photoUrls/videoUrls
 * to be rewritten again. A REJECTED report is explicitly "sent back for more
 * work" - by definition not a stable historical snapshot, and the status
 * most likely to actually be reworked in practice. Migrating it would create
 * DiagnosticMedia rows that could go stale the moment resetForRework() fires.
 * COMPLETED/APPROVED do not have this problem *at the point migrate() is run
 * against them*: saveInspectionAnswers() only permits writes while
 * IN_PROGRESS, so photoUrls/videoUrls cannot change while a request sits in
 * COMPLETED or APPROVED - excluding REJECTED removes the one status in this
 * set whose entire purpose is "about to be edited again". This is a scope
 * narrowing, not a claim that COMPLETED/APPROVED can never subsequently
 * change status themselves (COMPLETED can still be rejected via
 * rejectReport(), and a rejected-then-reworked-then-recompleted request
 * could in principle be migrated twice with different content between runs -
 * seed KNOWN RESIDUAL RISK below). resetForRework() itself is intentionally
 * NOT modified by this decision.
 *
 * CONSISTENCY MODEL: no new schema field is used to track "migration
 * complete". Completeness is computed on demand by comparing the CURRENT
 * legacy URL count for an (requestId, itemId) against the count of READY
 * DiagnosticMedia rows already migrated for it (storageKey prefixed
 * diagnostic-media-legacy/). While a request stays in COMPLETED or APPROVED,
 * this comparison is stable and safe to recompute every time (used
 * identically by DiagnosticRequestService's report dual-read helper).
 *
 * IDENTITY MECHANISM: DiagnosticMedia.sortOrder is NOT a globally unique or
 * DB-enforced value across all rows for an item (no unique index; the native
 * upload flow - DiagnosticMediaService.createIntent - defaults every new row
 * to sortOrder=0 and only assigns real positions via the explicit,
 * client-driven reorder()/replaceOrdering() action). The identity model here
 * does NOT rely on sortOrder being globally unique. It relies on: (1) this
 * service being the ONLY writer of DiagnosticMedia rows whose storageKey
 * starts with LEGACY_KEY_PREFIX ("diagnostic-media-legacy/") - the native
 * flow's keys start with "diagnostic-media/" (no "-legacy" segment), a
 * disjoint prefix; (2) every read of the migrated set (here and in
 * DiagnosticRequestService.resolveMediaUrlsForReport) filtering to that
 * prefix BEFORE interpreting sortOrder; (3) within that filtered subset,
 * exactly one row is ever created per (requestId, itemId, position), where
 * position is the 0-based index into the flat list [all photoUrls in order,
 * then all videoUrls in order] - see migrateItem(). Under those three
 * conditions sortOrder is deterministic and collision-free within the
 * legacy-scoped subset even though it is not globally unique.
 *
 * KNOWN RESIDUAL RISK (excluding REJECTED removes the direct/likely case;
 * this narrower case is NOT fixed here - closing it would require either
 * touching resetForRework()/rejectReport() to invalidate stale rows, or a
 * persisted per-row source-URL identity, both explicitly out of this
 * phase's scope): COMPLETED and APPROVED are themselves not fully terminal
 * either - a COMPLETED report can still be sent to REJECTED via
 * rejectReport(), reworked, and resubmitted to COMPLETED again. If migrate()
 * is run, then that full reject/rework/recomplete cycle happens with NEW
 * photoUrls/videoUrls of the exact same per-type counts as before, then
 * migrate() is run a second time, the completeness check cannot distinguish
 * the new data from the stale rows already created by the first run. This
 * is a narrower, two-migrate()-runs-bridging-a-full-cycle scenario, not the
 * one this change targets (a REJECTED snapshot never getting migrated at
 * all) - see the MEDIA-3.3 review report for why this residual case is
 * accepted for this phase.
 */
@Slf4j
@Service
public class LegacyMediaMigrationService {

    private static final String LEGACY_KEY_PREFIX = "diagnostic-media-legacy/";

    /**
     * COMPLETED/APPROVED only - REJECTED is deliberately excluded because it
     * is not a stable historical snapshot (resetForRework() legitimately
     * returns it to IN_PROGRESS for editing). See class Javadoc.
     */
    private static final Set<DiagnosticRequest.DiagnosticRequestStatus> STABLE_STATUSES = Set.of(
            DiagnosticRequest.DiagnosticRequestStatus.COMPLETED,
            DiagnosticRequest.DiagnosticRequestStatus.APPROVED
    );
    private static final Map<String, String> EXTENSION_BY_MIME = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "video/mp4", ".mp4"
    );

    private final DiagnosticRequestRepository requestRepository;
    private final InspectionAnswerRepository answerRepository;
    private final InspectionTemplateRepository templateRepository;
    private final DiagnosticMediaRepository mediaRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final StorageClient storageClient;
    private final String mediaBaseUrl;
    private final Path uploadDir;

    public LegacyMediaMigrationService(
            DiagnosticRequestRepository requestRepository,
            InspectionAnswerRepository answerRepository,
            InspectionTemplateRepository templateRepository,
            DiagnosticMediaRepository mediaRepository,
            MediaAssetRepository mediaAssetRepository,
            StorageClient storageClient,
            @Value("${media.base-url}") String mediaBaseUrl,
            @Value("${media.upload.dir}") String uploadDir) {
        this.requestRepository = requestRepository;
        this.answerRepository = answerRepository;
        this.templateRepository = templateRepository;
        this.mediaRepository = mediaRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.storageClient = storageClient;
        this.mediaBaseUrl = mediaBaseUrl;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    private record LegacyRef(String url, MediaType mediaType) {}

    public LegacyMediaMigrationResult migrate() {
        int migrated = 0;
        int skippedAlreadyMigrated = 0;
        int skippedUnresolvedTemplate = 0;
        int skippedNoLegacyMedia = 0;
        Map<String, Integer> failedByReason = new HashMap<>();

        List<DiagnosticRequest> candidates = requestRepository.findAll().stream()
                .filter(r -> STABLE_STATUSES.contains(r.getStatus()))
                .toList();

        for (DiagnosticRequest request : candidates) {
            if (request.getTemplateId() == null) {
                List<InspectionAnswer> answers = answerRepository.findByDiagnosticRequestId(request.getId());
                long legacyItemCount = answers.stream().filter(this::hasLegacyMedia).count();
                if (legacyItemCount > 0) {
                    log.warn("MEDIA-3.3 migration: request {} has legacy media but templateId is unresolved - skipping", request.getId());
                    skippedUnresolvedTemplate += (int) legacyItemCount;
                }
                continue;
            }

            for (InspectionAnswer answer : answerRepository.findByDiagnosticRequestId(request.getId())) {
                if (!hasLegacyMedia(answer)) {
                    skippedNoLegacyMedia++;
                    continue;
                }

                MigrateItemResult result;
                try {
                    result = migrateItem(request, answer);
                } catch (Exception e) {
                    // Phase 10 operational safety: one item's unexpected failure (storage
                    // client transport error, unforeseen data shape, etc.) must never abort
                    // the rest of the global sweep - every other request/item still gets a
                    // chance to migrate in this same run.
                    log.error("MEDIA-3.3 migration: request {} item {} - unexpected failure, skipping this item and continuing",
                            request.getId(), answer.getInspectionItemId(), e);
                    failedByReason.merge("UNEXPECTED_ERROR", 1, Integer::sum);
                    continue;
                }
                migrated += result.migrated();
                skippedAlreadyMigrated += result.alreadyMigrated();
                for (Map.Entry<String, Integer> e : result.failedByReason().entrySet()) {
                    failedByReason.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }

        log.info("MEDIA-3.3 migration complete: migrated={}, skippedAlreadyMigrated={}, " +
                        "skippedUnresolvedTemplate={}, skippedNoLegacyMedia={}, failed={}",
                migrated, skippedAlreadyMigrated, skippedUnresolvedTemplate, skippedNoLegacyMedia, failedByReason);

        return new LegacyMediaMigrationResult(migrated, skippedAlreadyMigrated,
                skippedUnresolvedTemplate, skippedNoLegacyMedia, failedByReason);
    }

    private boolean hasLegacyMedia(InspectionAnswer answer) {
        return (answer.getPhotoUrls() != null && !answer.getPhotoUrls().isEmpty())
                || (answer.getVideoUrls() != null && !answer.getVideoUrls().isEmpty());
    }

    private record MigrateItemResult(int migrated, int alreadyMigrated, Map<String, Integer> failedByReason) {}

    private MigrateItemResult migrateItem(DiagnosticRequest request, InspectionAnswer answer) {
        Map<String, Integer> failed = new HashMap<>();

        InspectionItem item = templateRepository.findItemById(answer.getInspectionItemId()).orElse(null);
        InspectionGroup group = item == null ? null : templateRepository.findGroupById(item.getGroupId()).orElse(null);
        boolean itemBelongsToTemplate = group != null && group.getTemplateId().equals(request.getTemplateId());
        if (!itemBelongsToTemplate) {
            log.warn("MEDIA-3.3 migration: request {} item {} could not be resolved against the request's template - skipping",
                    request.getId(), answer.getInspectionItemId());
            failed.merge("UNRESOLVED_ITEM", 1, Integer::sum);
            return new MigrateItemResult(0, 0, failed);
        }

        List<LegacyRef> combined = new ArrayList<>();
        for (String url : nullSafe(answer.getPhotoUrls())) combined.add(new LegacyRef(url, MediaType.PHOTO));
        for (String url : nullSafe(answer.getVideoUrls())) combined.add(new LegacyRef(url, MediaType.VIDEO));

        List<DiagnosticMedia> existingLegacy = mediaRepository.findByRequestAndItem(request.getId(), item.getId()).stream()
                .filter(m -> m.getStorageKey().startsWith(LEGACY_KEY_PREFIX))
                .toList();
        Set<Integer> alreadyMigratedPositions = existingLegacy.stream()
                .map(DiagnosticMedia::getSortOrder)
                .collect(java.util.stream.Collectors.toSet());

        if (alreadyMigratedPositions.size() >= combined.size() && !combined.isEmpty()) {
            return new MigrateItemResult(0, combined.size(), failed);
        }

        int migratedCount = 0;
        int alreadyCount = 0;
        for (int position = 0; position < combined.size(); position++) {
            if (alreadyMigratedPositions.contains(position)) {
                alreadyCount++;
                continue;
            }
            LegacyRef ref = combined.get(position);
            String reason = migrateOnePosition(request, item, position, ref);
            if (reason == null) {
                migratedCount++;
            } else {
                failed.merge(reason, 1, Integer::sum);
            }
        }

        return new MigrateItemResult(migratedCount, alreadyCount, failed);
    }

    /** Returns null on success, or a failure reason key. */
    private String migrateOnePosition(DiagnosticRequest request, InspectionItem item, int position, LegacyRef ref) {
        Optional<String> filename = resolveLegacyFilename(ref.url());
        if (filename.isEmpty()) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - legacy URL could not be resolved safely: {}",
                    request.getId(), item.getId(), position, ref.url());
            return "MISSING_LEGACY_FILE";
        }

        Path filePath = uploadDir.resolve(filename.get()).normalize();
        if (!filePath.startsWith(uploadDir) || !Files.isRegularFile(filePath) || !Files.isReadable(filePath)) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - legacy file missing/unreadable: {}",
                    request.getId(), item.getId(), position, filename.get());
            return "MISSING_LEGACY_FILE";
        }

        // Defense in depth against a symlink planted inside uploadDir pointing outside it:
        // normalize()+startsWith() above only reasons about the file's own (lexical) path, not
        // where a symlink actually resolves to - Files.isRegularFile()/isReadable() follow
        // symlinks by default, so a "innocent.jpg" symlink to an arbitrary file elsewhere would
        // otherwise pass every check above. toRealPath() resolves symlinks; re-checking
        // containment against the REAL path closes that gap. Fails closed (treated the same as
        // a missing file) on any resolution error.
        try {
            Path realFilePath = filePath.toRealPath();
            Path realUploadDir = uploadDir.toRealPath();
            if (!realFilePath.startsWith(realUploadDir)) {
                log.warn("MEDIA-3.3 migration: request {} item {} position {} - legacy file resolves outside upload dir (symlink?): {}",
                        request.getId(), item.getId(), position, filename.get());
                return "MISSING_LEGACY_FILE";
            }
        } catch (IOException e) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - failed to resolve real path for {}: {}",
                    request.getId(), item.getId(), position, filename.get(), e.getMessage());
            return "MISSING_LEGACY_FILE";
        }

        // Resolve uploader BEFORE touching R2 - if it cannot be recovered, skip the position
        // entirely without ever creating an orphaned R2 object (DiagnosticMedia.uploaderId is
        // NOT NULL, and fabricating an uploader is explicitly disallowed).
        Long uploaderId = mediaAssetRepository.findByStorageKey(filename.get())
                .map(a -> a.getUploaderId())
                .orElse(null);
        if (uploaderId == null) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - uploader could not be recovered for {}, skipping",
                    request.getId(), item.getId(), position, filename.get());
            return "UNRESOLVED_UPLOADER";
        }

        long sizeBytes;
        byte[] leadingBytes;
        try {
            sizeBytes = Files.size(filePath);
            try (InputStream probe = Files.newInputStream(filePath)) {
                leadingBytes = probe.readNBytes(MediaContentValidator.PROBE_LENGTH);
            }
        } catch (IOException e) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - failed to read legacy file {}: {}",
                    request.getId(), item.getId(), position, filename.get(), e.getMessage());
            return "UNREADABLE_LEGACY_FILE";
        }

        String detectedMimeType = MediaContentValidator.detectMimeType(leadingBytes);
        if (detectedMimeType == null) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - content not recognized as a supported media type: {}",
                    request.getId(), item.getId(), position, filename.get());
            return "INVALID_MEDIA_CONTENT";
        }

        String storageKey = LEGACY_KEY_PREFIX + request.getId() + "/" + UUID.randomUUID()
                + EXTENSION_BY_MIME.getOrDefault(detectedMimeType, "");

        try (InputStream upload = Files.newInputStream(filePath)) {
            storageClient.putObject(storageKey, upload, sizeBytes, detectedMimeType);
        } catch (Exception e) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - R2 upload failed for {}: {}",
                    request.getId(), item.getId(), position, filename.get(), e.getMessage());
            return "R2_UPLOAD_FAILED";
        }

        // headObject's port contract permits it to throw on a transport-level failure (it is
        // not required to swallow errors into an empty Optional the way putObject callers might
        // assume) - this call MUST be guarded the same way putObject is above, otherwise a
        // single R2 transport blip would propagate out of migrateOnePosition/migrateItem and
        // abort the entire global sweep (violates Phase 10: one item's failure must never stop
        // the rest of the batch).
        Optional<StorageClient.StoredObjectMetadata> headResult;
        try {
            headResult = storageClient.headObject(storageKey);
        } catch (Exception e) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - R2 verification threw for {}: {}",
                    request.getId(), item.getId(), position, filename.get(), e.getMessage());
            return "R2_VERIFICATION_FAILED";
        }
        if (headResult.isEmpty() || headResult.get().sizeBytes() != sizeBytes) {
            log.warn("MEDIA-3.3 migration: request {} item {} position {} - R2 verification failed for {}",
                    request.getId(), item.getId(), position, filename.get());
            return "R2_VERIFICATION_FAILED";
        }

        DiagnosticMedia media = new DiagnosticMedia();
        media.setDiagnosticRequestId(request.getId());
        media.setInspectionItemId(item.getId());
        media.setUploaderId(uploaderId);
        media.setStorageKey(storageKey);
        media.setMediaType(ref.mediaType());
        media.setMimeType(detectedMimeType);
        media.setSizeBytes(sizeBytes);
        media.setSortOrder(position);
        media.setUploadStatus(UploadStatus.READY);
        media.setProcessingStatus(ref.mediaType() == MediaType.PHOTO ? ProcessingStatus.NOT_REQUIRED : ProcessingStatus.PENDING);

        // The R2 object above is already uploaded AND verified at this point - putObject/
        // headObject and this DB save are two independent systems with no shared transaction
        // (mediaRepository.save() opens/commits its own transaction, per
        // DiagnosticMediaRepositoryAdapter's class-level @Transactional). A normal application
        // failure here (DB connection loss, lock timeout, constraint violation) would otherwise
        // leave the R2 object permanently orphaned: no DiagnosticMedia row means
        // MediaReconciliationService's sweeps - all of which start from a DB row, never from
        // listing the bucket - can never find it, and a rerun of this migration would generate a
        // brand new UUID-based storageKey rather than retrying/reusing this one. Best-effort
        // compensating delete, using the same StorageClient.deleteObject() contract
        // ("must not throw for an already-absent object") already relied on elsewhere
        // (DiagnosticMediaService's delete flow, MediaReconciliationService's DELETED sweep).
        try {
            mediaRepository.save(media);
        } catch (Exception e) {
            log.error("MEDIA-3.3 migration: request {} item {} position {} - DiagnosticMedia persistence " +
                            "failed AFTER successful R2 upload/verification for key {} - attempting compensating delete: {}",
                    request.getId(), item.getId(), position, storageKey, e.getMessage());
            try {
                storageClient.deleteObject(storageKey);
            } catch (Exception deleteEx) {
                log.error("MEDIA-3.3 migration: request {} item {} position {} - compensating delete of {} " +
                                "ALSO failed - manual cleanup may be required: {}",
                        request.getId(), item.getId(), position, storageKey, deleteEx.getMessage());
            }
            return "DB_PERSISTENCE_FAILED";
        }

        log.info("MEDIA-3.3 migration: request {} item {} position {} -> migrated (key={})",
                request.getId(), item.getId(), position, storageKey);
        return null;
    }

    /**
     * Mirrors MediaController.delete()'s already-proven-safe filename extraction/validation
     * exactly (same rejections: prefix mismatch, "..", "/", "\\", blank) rather than
     * reimplementing new logic, plus an additional normalize()+startsWith() containment check
     * as defense in depth before any filesystem access is attempted.
     */
    private Optional<String> resolveLegacyFilename(String legacyUrl) {
        if (legacyUrl == null || !legacyUrl.startsWith(mediaBaseUrl)) {
            return Optional.empty();
        }
        String filename = legacyUrl.substring(mediaBaseUrl.length()).replaceAll("^/+", "");
        if (filename.isBlank() || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return Optional.empty();
        }
        return Optional.of(filename);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
