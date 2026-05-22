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
    private String rejectedReason;
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
        response.setCreatedAt(request.getCreatedAt());
        response.setUpdatedAt(request.getUpdatedAt());
        return response;
    }
}