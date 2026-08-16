package com.example.autoauction.admin.web;

import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.common.web.dto.FailedOperationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * R-2 Phase 7: minimum viable read-only visibility into failed financial operations and
 * their recovery status. No manual retry endpoint - automatic recovery
 * (FinancialRecoveryScheduler) is the primary mechanism, per the explicit instruction not
 * to add manual retry controls unless necessary for correctness.
 */
@RestController
@RequestMapping("/api/v1/admin/failed-operations")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Сбойные финансовые операции (Администратор)", description = "Наблюдаемость восстановления финансовых операций")
public class AdminFailedOperationController {

    private final FailedOperationRepository failedOperationRepository;

    @GetMapping
    @Operation(summary = "Список сбойных финансовых операций (опционально отфильтровано по статусу)")
    public ResponseEntity<List<FailedOperationResponse>> list(
            @RequestParam(required = false) String status
    ) {
        List<FailedOperation> operations = (status == null || status.isBlank())
                ? failedOperationRepository.findAll()
                : failedOperationRepository.findByStatus(status.toUpperCase());

        List<FailedOperationResponse> response = operations.stream()
                .sorted(Comparator.comparing(FailedOperation::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(FailedOperationResponse::fromDomain)
                .toList();

        return ResponseEntity.ok(response);
    }
}
