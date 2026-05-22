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
    private String rejectedReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public enum DiagnosticRequestStatus {
        PENDING,        // Ожидает диагноста
        IN_PROGRESS,    // В работе у диагноста
        COMPLETED,      // Отчёт готов, на проверке у админа
        APPROVED,       // Одобрен админом
        REJECTED        // Отклонён админом
    }
}