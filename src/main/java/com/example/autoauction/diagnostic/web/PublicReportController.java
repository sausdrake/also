package com.example.autoauction.diagnostic.web;

import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.media.application.DiagnosticMediaService;
import com.example.autoauction.diagnostic.media.web.dto.MediaReadUrlResponse;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
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
    public ResponseEntity<DiagnosticRequestResponse> getReport(@PathVariable Long requestId) {
        DiagnosticRequestResponse report = requestService.getRequestDetails(requestId);
        if (report.getStatus() == null || !report.getStatus().equals("APPROVED")) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/by-vehicle/{vehicleId}")
    @Operation(summary = "Получить одобренный отчёт по ID автомобиля")
    public ResponseEntity<DiagnosticRequestResponse> getReportByVehicle(@PathVariable Long vehicleId) {
        try {
            return ResponseEntity.ok(requestService.getApprovedReportForVehicle(vehicleId));
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
}
