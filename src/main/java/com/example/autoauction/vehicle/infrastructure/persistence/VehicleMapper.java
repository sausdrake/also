package com.example.autoauction.vehicle.infrastructure.persistence;

import com.example.autoauction.vehicle.domain.Vehicle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class VehicleMapper {

    public JpaVehicleEntity toEntity(Vehicle domain) {
        if (domain == null) return null;

        JpaVehicleEntity entity = new JpaVehicleEntity();
        entity.setId(domain.getId());
        entity.setBrand(domain.getBrand());
        entity.setModel(domain.getModel());
        entity.setYear(domain.getYear());
        entity.setVin(domain.getVin());
        entity.setLicensePlate(domain.getLicensePlate());
        entity.setType(domain.getType());
        entity.setColor(domain.getColor());
        entity.setMileage(domain.getMileage());
        entity.setEngineCapacity(domain.getEngineCapacity());
        entity.setFuelType(domain.getFuelType());
        entity.setTransmission(domain.getTransmission());
        entity.setDescription(domain.getDescription());
        entity.setStatus(domain.getStatus());
        entity.setDiagnosticId(domain.getDiagnosticId());
        entity.setDiagnosticName(domain.getDiagnosticName());
        entity.setDiagnosticRequestId(domain.getDiagnosticRequestId());
        entity.setAdminId(domain.getAdminId());
        entity.setAdminName(domain.getAdminName());
        entity.setRejectionReason(domain.getRejectionReason());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setSubmittedAt(domain.getSubmittedAt());
        entity.setReviewedAt(domain.getReviewedAt());
        return entity;
    }

    public Vehicle toDomain(JpaVehicleEntity entity) {
        if (entity == null) return null;

        Vehicle vehicle = new Vehicle(
                entity.getBrand(),
                entity.getModel(),
                entity.getYear(),
                entity.getVin(),
                entity.getType(),
                entity.getDiagnosticId(),
                entity.getDiagnosticName()
        );

        vehicle.setId(entity.getId());
        vehicle.setDiagnosticRequestId(entity.getDiagnosticRequestId());
        vehicle.setLicensePlate(entity.getLicensePlate());
        vehicle.setColor(entity.getColor());
        vehicle.setMileage(entity.getMileage());
        vehicle.setEngineCapacity(entity.getEngineCapacity());
        vehicle.setFuelType(entity.getFuelType());
        vehicle.setTransmission(entity.getTransmission());
        vehicle.setDescription(entity.getDescription());
        vehicle.setStatus(entity.getStatus());
        vehicle.setAdminId(entity.getAdminId());
        vehicle.setAdminName(entity.getAdminName());
        vehicle.setRejectionReason(entity.getRejectionReason());
        vehicle.setCreatedAt(entity.getCreatedAt());
        vehicle.setUpdatedAt(entity.getUpdatedAt());
        vehicle.setSubmittedAt(entity.getSubmittedAt());
        vehicle.setReviewedAt(entity.getReviewedAt());

        return vehicle;
    }
}