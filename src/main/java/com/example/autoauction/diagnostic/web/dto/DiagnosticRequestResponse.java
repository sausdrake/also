package com.example.autoauction.diagnostic.web.dto;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class DiagnosticRequestResponse {
    private Long id;
    private Long userId;
    private String userName;
    private String brand;
    private String model;
    private Integer year;
    private String vin;
    private BigDecimal depositFrozen;
    private String status;
    private Long assignedTo;
    private String diagnosticName;

    /**
     * Sale-UI: the Vehicle auto-created from this request once its report is approved (see
     * {@code DiagnosticRequestService.approveReport} -> {@code VehicleService
     * .createFromDiagnosticRequest}) - null until then. Populated post-hoc by the service layer
     * (mirrors this class's existing diagnosticName/userName enrichment pattern), never by
     * {@link #fromDomain} itself, since {@link DiagnosticRequest} carries no such reference.
     */
    private Long vehicleId;
    private String rejectedReason;
    private String rejectionCategory;

    /**
     * Seller-violation-resolution UI: the SellerDiagnosticCommitment's own status
     * (FROZEN/AWAITING_FINALIZATION/FINALIZED) for this request, null if no commitment was
     * ever frozen. Populated post-hoc, same enrichment pattern as vehicleId.
     */
    private String commitmentStatus;

    /**
     * Seller-violation-resolution UI: the existing seller-violation resolution for this
     * request's commitment, if it has been finalized as one - null otherwise. Populated
     * post-hoc from SellerDiagnosticCommitmentService, never computed here.
     */
    private SellerViolationResolutionResponse violationResolution;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Для диагностика
    private InspectionTemplate template;
    private List<InspectionAnswer> existingAnswers;
    private List<InspectionAnswer> answers;

    public static DiagnosticRequestResponse fromDomain(DiagnosticRequest request) {
        DiagnosticRequestResponse response = new DiagnosticRequestResponse();
        response.setId(request.getId());
        response.setUserId(request.getUserId());
        response.setBrand(request.getBrand());
        response.setModel(request.getModel());
        response.setYear(request.getYear());
        response.setVin(request.getVin());
        response.setDepositFrozen(request.getDepositFrozen());
        response.setStatus(request.getStatus().name());
        response.setAssignedTo(request.getAssignedTo());
        response.setRejectedReason(request.getRejectedReason());
        response.setRejectionCategory(request.getRejectionCategory() != null ? request.getRejectionCategory().name() : null);
        response.setCreatedAt(request.getCreatedAt());
        response.setUpdatedAt(request.getUpdatedAt());
        return response;
    }
}