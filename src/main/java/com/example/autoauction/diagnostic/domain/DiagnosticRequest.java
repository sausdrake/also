package com.example.autoauction.diagnostic.domain;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class DiagnosticRequest {
    private Long id;
    private Long userId;
    private String brand;
    private String model;
    private Integer year;
    private String vin;
    private BigDecimal depositFrozen;
    private DiagnosticRequestStatus status;
    private Long assignedTo;
    private Long templateId;
    private String rejectedReason;
    private DiagnosticRejectionCategory rejectionCategory;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // MVP-005: re-diagnostic link and audit trail (all null for an ordinary first request)
    private Long previousDiagnosticRequestId;
    private Long vehicleId;
    private RediagnosticReason rediagnosticReason;
    private String rediagnosticComment;
    private Long rediagnosticOrderedBy;
    private OffsetDateTime rediagnosticOrderedAt;

    /** MVP-005: true for a request created by an admin-ordered re-diagnostic. */
    public boolean isRediagnostic() {
        return previousDiagnosticRequestId != null;
    }

    public enum DiagnosticRequestStatus {
        PENDING,        // Ожидает диагноста
        IN_PROGRESS,    // В работе у диагноста
        COMPLETED,      // Отчёт готов, на проверке у админа
        APPROVED,       // Одобрен админом
        REJECTED        // Отклонён админом
    }
}