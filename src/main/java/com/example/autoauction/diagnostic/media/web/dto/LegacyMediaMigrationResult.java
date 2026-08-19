package com.example.autoauction.diagnostic.media.web.dto;

import java.util.Map;

/**
 * MEDIA-3.3: summary of one legacy-media migration run. Counts are per legacy
 * URL (one photo or video reference), not per InspectionAnswer or per
 * request. failedByReason keys are one of: MISSING_LEGACY_FILE,
 * UNREADABLE_LEGACY_FILE, INVALID_MEDIA_CONTENT, R2_UPLOAD_FAILED,
 * R2_VERIFICATION_FAILED, DB_PERSISTENCE_FAILED, UNRESOLVED_TEMPLATE,
 * UNRESOLVED_ITEM, UNRESOLVED_UPLOADER, UNEXPECTED_ERROR.
 */
public record LegacyMediaMigrationResult(
        int migrated,
        int skippedAlreadyMigrated,
        int skippedUnresolvedTemplate,
        int skippedNoLegacyMedia,
        Map<String, Integer> failedByReason) {
}
