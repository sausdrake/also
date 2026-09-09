package com.example.autoauction.vehicle.infrastructure.persistence;

import com.example.autoauction.vehicle.domain.FuelType;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Slf4j
@Entity
@Table(name = "vehicles",
        uniqueConstraints = @UniqueConstraint(columnNames = "diagnostic_request_id"))
public class JpaVehicleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String brand;

    @Column(nullable = false, length = 50)
    private String model;

    @Column(name = "production_year", nullable = false)
    private Integer year;

    @Column(unique = true, nullable = false, length = 17)
    private String vin;

    @Column(name = "license_plate", length = 20)
    private String licensePlate;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private VehicleType type;

    @Column(length = 30)
    private String color;

    private Integer mileage;

    @Column(name = "engine_capacity")
    private Double engineCapacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuel_type")
    private FuelType fuelType;

    @Column(length = 30)
    private String transmission;

    @Column(name = "description", length = 10000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleStatus status;

    @Column(name = "diagnostic_id", nullable = false)
    private Long diagnosticId;

    @Column(name = "diagnostic_name")
    private String diagnosticName;

    // X3-C: see Vehicle.diagnosticRequestId's field Javadoc. Nullable - historical/manual
    // vehicles predate or fall outside this relationship.
    @Column(name = "diagnostic_request_id")
    private Long diagnosticRequestId;

    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "admin_name")
    private String adminName;

    @Column(name = "rejection_reason", length = 10000)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    // Конструктор по умолчанию для JPA
    protected JpaVehicleEntity() {}

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }

    public String getLicensePlate() { return licensePlate; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }

    public VehicleType getType() { return type; }
    public void setType(VehicleType type) { this.type = type; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public Integer getMileage() { return mileage; }
    public void setMileage(Integer mileage) { this.mileage = mileage; }

    public Double getEngineCapacity() { return engineCapacity; }
    public void setEngineCapacity(Double engineCapacity) { this.engineCapacity = engineCapacity; }

    public FuelType getFuelType() { return fuelType; }
    public void setFuelType(FuelType fuelType) { this.fuelType = fuelType; }

    public String getTransmission() { return transmission; }
    public void setTransmission(String transmission) { this.transmission = transmission; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public VehicleStatus getStatus() { return status; }
    public void setStatus(VehicleStatus status) { this.status = status; }

    public Long getDiagnosticId() { return diagnosticId; }
    public void setDiagnosticId(Long diagnosticId) { this.diagnosticId = diagnosticId; }

    public String getDiagnosticName() { return diagnosticName; }
    public void setDiagnosticName(String diagnosticName) { this.diagnosticName = diagnosticName; }

    public Long getDiagnosticRequestId() { return diagnosticRequestId; }
    public void setDiagnosticRequestId(Long diagnosticRequestId) { this.diagnosticRequestId = diagnosticRequestId; }

    public Long getAdminId() { return adminId; }
    public void setAdminId(Long adminId) { this.adminId = adminId; }

    public String getAdminName() { return adminName; }
    public void setAdminName(String adminName) { this.adminName = adminName; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }

    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}