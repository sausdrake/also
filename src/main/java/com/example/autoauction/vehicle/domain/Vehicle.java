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

    /**
     * X3-C: the DiagnosticRequest that represents this Vehicle's CURRENT diagnostic cycle -
     * the authoritative pointer used by AuctionCommitmentResolver to deterministically find
     * the eligible SellerDiagnosticCommitment for a new Auction. Null for legacy/manual
     * vehicles that predate this relationship (or were created outside the diagnostic-request
     * workflow) - such a vehicle remains auction-ineligible until some future commitment-
     * producing workflow establishes this link (explicitly out of scope here, per the X3-C
     * business-decision lock on the manual-vehicle path).
     *
     * Written exactly once today, by VehicleService.createFromDiagnosticRequest's create
     * branch, at Vehicle creation. There is currently no second writer - the re-diagnostic
     * workflow that would update this pointer on a later cycle does not exist yet and is out
     * of scope for X3-C (see that method's own Javadoc for the VIN-dedup branch this
     * deliberately does NOT touch).
     */
    private Long diagnosticRequestId;

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
    public Long getDiagnosticRequestId() { return diagnosticRequestId; }
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

    /**
     * AUC-NOSALE-01 (approved policy): an auction of this vehicle ended without a sale - EXPIRED,
     * or CANCELLED (from CREATED, while the vehicle is still APPROVED, or from ACTIVE, while it
     * is IN_AUCTION). The vehicle needs a fresh diagnostic cycle before it can be auctioned
     * again. Any other status means the vehicle was not on this auction - fail closed.
     */
    public void markNeedsRediagnosticAfterNoSale() {
        if (this.status != VehicleStatus.IN_AUCTION && this.status != VehicleStatus.APPROVED) {
            throw new IllegalStateException("Only IN_AUCTION or APPROVED can require re-diagnostic after an auction without a sale");
        }
        this.status = VehicleStatus.NEEDS_REDIAGNOSTIC;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * MVP-005: the re-diagnostic report was approved - the SAME vehicle becomes auctionable again
     * and its current diagnostic pointer moves to the new request (the latest approved report),
     * so a new auction binds the new request's commitment. Earlier requests stay reachable
     * through the re-diagnostic chain.
     */
    public void completeRediagnostic(Long newDiagnosticRequestId) {
        if (this.status != VehicleStatus.NEEDS_REDIAGNOSTIC) {
            throw new IllegalStateException("Only NEEDS_REDIAGNOSTIC can complete a re-diagnostic");
        }
        this.diagnosticRequestId = java.util.Objects.requireNonNull(newDiagnosticRequestId, "newDiagnosticRequestId");
        this.status = VehicleStatus.APPROVED;
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

    /**
     * X3-C: see {@link #diagnosticRequestId}'s field Javadoc for the single-writer discipline.
     * Plain setter, matching this class's existing convention (setAdminId, setRejectionReason)
     * rather than a guarded business method - this is a reference fact, not a state machine.
     */
    public void setDiagnosticRequestId(Long diagnosticRequestId) {
        this.diagnosticRequestId = diagnosticRequestId;
    }
}