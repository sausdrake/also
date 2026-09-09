package com.example.autoauction.vehicle.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class Vehicle {
    private Long id;
    private String brand;
    private String model;
    private Integer year;
    private String vin;
    private String licensePlate;
    private VehicleType type;
    private String color;
    private Integer mileage;
    private Double engineCapacity;
    private FuelType fuelType;
    private String transmission;
    private String description;

    private VehicleStatus status;

    // Кто создал (диагностик)
    private Long diagnosticId;
    private String diagnosticName;

    // Кто одобрил (админ)
    private Long adminId;
    private String adminName;
    private String rejectionReason;     // Причина отклонения

    // Даты
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime submittedAt;  // Дата отправки на проверку
    private OffsetDateTime reviewedAt;    // Дата проверки админом

    // Конструктор для создания черновика
    public Vehicle(String brand, String model, Integer year, String vin,
                   VehicleType type, Long diagnosticId, String diagnosticName) {
        this.brand = brand;
        this.model = model;
        this.year = year;
        this.vin = vin;
        this.type = type;
        this.diagnosticId = diagnosticId;
        this.diagnosticName = diagnosticName;
        this.status = VehicleStatus.DRAFT;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    // Геттеры
    public Long getId() { return id; }
    public String getBrand() { return brand; }
    public String getModel() { return model; }
    public Integer getYear() { return year; }
    public String getVin() { return vin; }
    public String getLicensePlate() { return licensePlate; }
    public VehicleType getType() { return type; }
    public String getColor() { return color; }
    public Integer getMileage() { return mileage; }
    public Double getEngineCapacity() { return engineCapacity; }
    public FuelType getFuelType() { return fuelType; }
    public String getTransmission() { return transmission; }
    public String getDescription() { return description; }
    public VehicleStatus getStatus() { return status; }
    public Long getDiagnosticId() { return diagnosticId; }
    public String getDiagnosticName() { return diagnosticName; }
    public Long getAdminId() { return adminId; }
    public String getAdminName() { return adminName; }
    public String getRejectionReason() { return rejectionReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }

    // Сеттеры для обновления полей
    public void setId(Long id) { this.id = id; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }
    public void setColor(String color) { this.color = color; }
    public void setMileage(Integer mileage) { this.mileage = mileage; }
    public void setEngineCapacity(Double engineCapacity) { this.engineCapacity = engineCapacity; }
    public void setFuelType(FuelType fuelType) { this.fuelType = fuelType; }
    public void setTransmission(String transmission) { this.transmission = transmission; }
    public void setDescription(String description) { this.description = description; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    // Бизнес-методы
    public void submitForReview() {
        if (this.status != VehicleStatus.DRAFT && this.status != VehicleStatus.NEEDS_FIXES) {
            throw new IllegalStateException("Only DRAFT or NEEDS_FIXES can be submitted");
        }
        this.status = VehicleStatus.PENDING_REVIEW;
        this.submittedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void approve(Long adminId, String adminName) {
        if (this.status != VehicleStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Only PENDING_REVIEW can be approved");
        }
        this.status = VehicleStatus.APPROVED;
        this.adminId = adminId;
        this.adminName = adminName;
        this.reviewedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void reject(Long adminId, String adminName, String reason) {
        if (this.status != VehicleStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Only PENDING_REVIEW can be rejected");
        }
        this.status = VehicleStatus.NEEDS_FIXES;
        this.adminId = adminId;
        this.adminName = adminName;
        this.rejectionReason = reason;
        this.reviewedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void markInAuction() {
        if (this.status != VehicleStatus.APPROVED) {
            throw new IllegalStateException("Only APPROVED can be moved to auction");
        }
        this.status = VehicleStatus.IN_AUCTION;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markAsSold() {
        if (this.status != VehicleStatus.IN_AUCTION) {
            throw new IllegalStateException("Only IN_AUCTION can be sold");
        }
        this.status = VehicleStatus.SOLD;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * X3-A (SALE_FAILED outcomes B, BUYER_FAULT): the vehicle returns to the active pool with
     * no fault requiring re-diagnosis. Deliberately does NOT touch adminId/adminName/
     * reviewedAt/rejectionReason - those record the original PENDING_REVIEW->APPROVED review
     * event, which genuinely happened and is not being re-performed here; only status and
     * updatedAt change.
     */
    public void returnToApproved() {
        if (this.status != VehicleStatus.IN_AUCTION) {
            throw new IllegalStateException("Only IN_AUCTION can be returned to APPROVED");
        }
        this.status = VehicleStatus.APPROVED;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * X3-A (SALE_FAILED outcomes PLATFORM_FAULT, C): the vehicle requires a fresh diagnostic
     * cycle before it can be re-auctioned. The workflow that performs that cycle (a new
     * DiagnosticRequest, independent-diagnostician assignment, eventual re-approval) is
     * explicitly out of scope for X3-A - this method only performs the one status transition.
     */
    public void markNeedsRediagnostic() {
        if (this.status != VehicleStatus.IN_AUCTION) {
            throw new IllegalStateException("Only IN_AUCTION can require re-diagnostic");
        }
        this.status = VehicleStatus.NEEDS_REDIAGNOSTIC;
        this.updatedAt = OffsetDateTime.now();
    }
    // Добавь в класс Vehicle следующие методы:

    public void setStatus(VehicleStatus status) {
        this.status = status;
    }

    public void setAdminId(Long adminId) {
        this.adminId = adminId;
    }

    public void setAdminName(String adminName) {
        this.adminName = adminName;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setSubmittedAt(OffsetDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public void setReviewedAt(OffsetDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}