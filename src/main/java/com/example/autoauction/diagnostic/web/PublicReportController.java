package com.example.autoauction.diagnostic.web;

import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.media.application.DiagnosticMediaService;
import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.web.dto.MediaReadUrlResponse;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.diagnostic.web.dto.PublicDiagnosticReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/reports")
@RequiredArgsConstructor
@Tag(name = "Публичные отчёты", description = "Просмотр диагностических отчётов без авторизации")
public class PublicReportController {

    private final DiagnosticRequestService requestService;
    private final DiagnosticMediaService mediaService;

    @GetMapping("/{requestId}")
    @Operation(summary = "Просмотр одобренного диагностического отчёта")
    public ResponseEntity<PublicDiagnosticReportResponse> getReport(@PathVariable Long requestId) {
        DiagnosticRequestResponse report = requestService.getRequestDetails(requestId);
        if (report.getStatus() == null || !report.getStatus().equals("APPROVED")) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(PublicDiagnosticReportResponse.from(report));   // SEC-12: public allowlist
    }

    @GetMapping("/by-vehicle/{vehicleId}")
    @Operation(summary = "Получить одобренный отчёт по ID автомобиля")
    public ResponseEntity<PublicDiagnosticReportResponse> getReportByVehicle(@PathVariable Long vehicleId) {
        try {
            return ResponseEntity.ok(PublicDiagnosticReportResponse.from(requestService.getApprovedReportForVehicle(vehicleId)));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * MEDIA-3.2: public read-URL for media belonging to an APPROVED report
     * only. The object is never made public merely because it exists in R2 -
     * a fresh, short-lived presigned URL is issued only after re-verifying
     * the owning request's current status here.
     */
    @GetMapping("/media/{mediaId}/url")
    @Operation(summary = "Получить ссылку на медиафайл одобренного отчёта")
    public ResponseEntity<MediaReadUrlResponse> getMediaReadUrl(@PathVariable Long mediaId) {
        try {
            return ResponseEntity.ok(mediaService.getReadUrlForApprovedReport(mediaId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The only two exception types getReadUrlForApprovedReport throws for a
            // legitimately inaccessible media (not found / not approved / not READY) - both
            // deliberately map to 404 here, not their normal GlobalExceptionHandler status
            // (400/409), so a public unauthenticated caller cannot distinguish "does not
            // exist" from "exists but is not yet approved". Anything else (infrastructure
            // failures, SDK exceptions, bugs) is NOT caught here and propagates to
            // GlobalExceptionHandler for a proper 500 + logged stack trace, instead of being
            // silently misreported as "not found".
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * MEDIA-3.4 Phase 11: public read-URL for a processed video's thumbnail,
     * belonging to an APPROVED report only - same visibility boundary as
     * getMediaReadUrl() above (loadVisibleApprovedReportMedia() re-enforces
     * not-found/not-approved/not-READY identically, mapped to the same
     * blanket 404). Deliberately NOT collapsed into that same catch: a
     * thumbnail that hasn't been generated yet (thumbnailStorageKey == null)
     * is a distinct, legitimate 409 - it leaks no information a public,
     * already-APPROVED-report caller doesn't already have, unlike "not
     * found"/"not approved" which must stay indistinguishable from each
     * other.
     */
    @GetMapping("/media/{mediaId}/thumbnail-url")
    @Operation(summary = "Получить ссылку на превью видео одобренного отчёта")
    public ResponseEntity<MediaReadUrlResponse> getMediaThumbnailReadUrl(@PathVariable Long mediaId) {
        DiagnosticMedia media;
        try {
            media = mediaService.loadVisibleApprovedReportMedia(mediaId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(mediaService.thumbnailReadUrlFor(media));
    }
}
