package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticRequestMapper {

    public JpaDiagnosticRequestEntity toEntity(DiagnosticRequest domain) {
        if (domain == null) return null;

        JpaDiagnosticRequestEntity entity = new JpaDiagnosticRequestEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setBrand(domain.getBrand());
        entity.setModel(domain.getModel());
        entity.setYear(domain.getYear());
        entity.setVin(domain.getVin());
        entity.setDepositFrozen(domain.getDepositFrozen());
        entity.setStatus(domain.getStatus());
        entity.setAssignedTo(domain.getAssignedTo());
        entity.setRejectedReason(domain.getRejectedReason());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public DiagnosticRequest toDomain(JpaDiagnosticRequestEntity entity) {
        if (entity == null) return null;

        DiagnosticRequest domain = new DiagnosticRequest();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setBrand(entity.getBrand());
        domain.setModel(entity.getModel());
        domain.setYear(entity.getYear());
        domain.setVin(entity.getVin());
        domain.setDepositFrozen(entity.getDepositFrozen());
        domain.setStatus(entity.getStatus());
        domain.setAssignedTo(entity.getAssignedTo());
        domain.setRejectedReason(entity.getRejectedReason());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }
}