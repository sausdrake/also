package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "diagnostic_requests")
@Data
public class JpaDiagnosticRequestEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "\"year\"", nullable = false)
    private Integer year;

    @Column(nullable = false, length = 17)
    private String vin;

    @Column(name = "deposit_frozen", nullable = false, precision = 19, scale = 2)
    private BigDecimal depositFrozen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiagnosticRequest.DiagnosticRequestStatus status;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "rejected_reason", length = 1000)
    private String rejectedReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
        if (status == null) {
            status = DiagnosticRequest.DiagnosticRequestStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}