package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
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

    // PHASE 2: nullable - only set by rejectReport, never by approveReport or older rows.
    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_category", length = 50)
    private DiagnosticRejectionCategory rejectionCategory;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "previous_diagnostic_request_id")
    private Long previousDiagnosticRequestId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rediagnostic_reason", length = 32)
    private com.example.autoauction.diagnostic.domain.RediagnosticReason rediagnosticReason;

    @Column(name = "rediagnostic_comment", length = 2000)
    private String rediagnosticComment;

    @Column(name = "rediagnostic_ordered_by")
    private Long rediagnosticOrderedBy;

    @Column(name = "rediagnostic_ordered_at")
    private OffsetDateTime rediagnosticOrderedAt;

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