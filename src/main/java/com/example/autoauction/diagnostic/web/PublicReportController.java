package com.example.autoauction.diagnostic.web;

import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
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
}
