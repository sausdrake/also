package com.example.autoauction.vehicle.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VehicleTest {

    private Vehicle vehicle;
    private final Long diagnosticId = 16L;
    private final String diagnosticName = "diagnostic";
    private final Long adminId = 1L;
    private final String adminName = "admin";

    @BeforeEach
    void setUp() {
        vehicle = new Vehicle(
                "BMW",
                "M5",
                2023,
                "WBSDE91070CZ12345",
                VehicleType.SEDAN,
                diagnosticId,
                diagnosticName
        );
    }

    @Test
    void constructor_ShouldCreateVehicleWithCorrectInitialState() {
        assertNotNull(vehicle);
        assertEquals("BMW", vehicle.getBrand());
        assertEquals("M5", vehicle.getModel());
        assertEquals(2023, vehicle.getYear());
        assertEquals("WBSDE91070CZ12345", vehicle.getVin());
        assertEquals(VehicleType.SEDAN, vehicle.getType());
        assertEquals(diagnosticId, vehicle.getDiagnosticId());
        assertEquals(diagnosticName, vehicle.getDiagnosticName());
        assertEquals(VehicleStatus.DRAFT, vehicle.getStatus());
        assertNotNull(vehicle.getCreatedAt());
        assertNotNull(vehicle.getUpdatedAt());
    }

    @Test
    void submitForReview_ShouldChangeStatusToPendingReview() {
        // when
        vehicle.submitForReview();

        // then
        assertEquals(VehicleStatus.PENDING_REVIEW, vehicle.getStatus());
        assertNotNull(vehicle.getSubmittedAt());
    }

    @Test
    void submitForReview_WhenNotDraft_ShouldThrowException() {
        // given
        vehicle.submitForReview();

        // when & then
        assertThrows(IllegalStateException.class, () -> vehicle.submitForReview());
    }

    @Test
    void approve_ShouldChangeStatusToApproved() {
        // given
        vehicle.submitForReview();

        // when
        vehicle.approve(adminId, adminName);

        // then
        assertEquals(VehicleStatus.APPROVED, vehicle.getStatus());
        assertEquals(adminId, vehicle.getAdminId());
        assertEquals(adminName, vehicle.getAdminName());
        assertNotNull(vehicle.getReviewedAt());
    }

    @Test
    void approve_WhenNotPendingReview_ShouldThrowException() {
        // given
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);

        // when & then
        assertThrows(IllegalStateException.class, () -> vehicle.approve(adminId, adminName));
    }

    @Test
    void reject_ShouldChangeStatusToNeedsFixes() {
        // given
        vehicle.submitForReview();
        String reason = "Не хватает фотографий";

        // when
        vehicle.reject(adminId, adminName, reason);

        // then
        assertEquals(VehicleStatus.NEEDS_FIXES, vehicle.getStatus());
        assertEquals(adminId, vehicle.getAdminId());
        assertEquals(adminName, vehicle.getAdminName());
        assertEquals(reason, vehicle.getRejectionReason());
        assertNotNull(vehicle.getReviewedAt());
    }

    @Test
    void markInAuction_ShouldChangeStatusToInAuction() {
        // given
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);

        // when
        vehicle.markInAuction();

        // then
        assertEquals(VehicleStatus.IN_AUCTION, vehicle.getStatus());
    }

    @Test
    void markInAuction_WhenNotApproved_ShouldThrowException() {
        // given
        vehicle.submitForReview();

        // when & then
        assertThrows(IllegalStateException.class, () -> vehicle.markInAuction());
    }

    @Test
    void markAsSold_ShouldChangeStatusToSold() {
        // given
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        vehicle.markInAuction();

        // when
        vehicle.markAsSold();

        // then
        assertEquals(VehicleStatus.SOLD, vehicle.getStatus());
    }

    // ==================== X3-A: returnToApproved() ====================

    @Test
    void returnToApproved_WhenInAuction_ShouldChangeStatusToApproved() {
        // given
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        vehicle.markInAuction();

        // when
        vehicle.returnToApproved();

        // then
        assertEquals(VehicleStatus.APPROVED, vehicle.getStatus());
    }

    @Test
    void returnToApproved_ShouldPreserveOriginalApprovalMetadata() {
        // given: the original review event really happened - returnToApproved() must not
        // re-touch adminId/adminName/reviewedAt/rejectionReason, it is not a new review.
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        var reviewedAtBefore = vehicle.getReviewedAt();
        vehicle.markInAuction();

        // when
        vehicle.returnToApproved();

        // then
        assertEquals(adminId, vehicle.getAdminId());
        assertEquals(adminName, vehicle.getAdminName());
        assertEquals(reviewedAtBefore, vehicle.getReviewedAt());
        assertNull(vehicle.getRejectionReason());
    }

    @Test
    void returnToApproved_WhenDraft_ShouldThrowException() {
        assertThrows(IllegalStateException.class, () -> vehicle.returnToApproved());
    }

    @Test
    void returnToApproved_WhenPendingReview_ShouldThrowException() {
        vehicle.submitForReview();
        assertThrows(IllegalStateException.class, () -> vehicle.returnToApproved());
    }

    @Test
    void returnToApproved_WhenApproved_ShouldThrowException() {
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        assertThrows(IllegalStateException.class, () -> vehicle.returnToApproved());
    }

    @Test
    void returnToApproved_WhenAlreadySold_ShouldThrowException() {
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        vehicle.markInAuction();
        vehicle.markAsSold();
        assertThrows(IllegalStateException.class, () -> vehicle.returnToApproved());
    }

    @Test
    void returnToApproved_WhenNeedsFixes_ShouldThrowException() {
        vehicle.submitForReview();
        vehicle.reject(adminId, adminName, "Не хватает фотографий");
        assertThrows(IllegalStateException.class, () -> vehicle.returnToApproved());
    }

    // ==================== X3-A: markNeedsRediagnostic() ====================

    @Test
    void markNeedsRediagnostic_WhenInAuction_ShouldChangeStatusToNeedsRediagnostic() {
        // given
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        vehicle.markInAuction();

        // when
        vehicle.markNeedsRediagnostic();

        // then
        assertEquals(VehicleStatus.NEEDS_REDIAGNOSTIC, vehicle.getStatus());
    }

    @Test
    void markNeedsRediagnostic_WhenDraft_ShouldThrowException() {
        assertThrows(IllegalStateException.class, () -> vehicle.markNeedsRediagnostic());
    }

    @Test
    void markNeedsRediagnostic_WhenPendingReview_ShouldThrowException() {
        vehicle.submitForReview();
        assertThrows(IllegalStateException.class, () -> vehicle.markNeedsRediagnostic());
    }

    @Test
    void markNeedsRediagnostic_WhenApproved_ShouldThrowException() {
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        assertThrows(IllegalStateException.class, () -> vehicle.markNeedsRediagnostic());
    }

    @Test
    void markNeedsRediagnostic_WhenAlreadySold_ShouldThrowException() {
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        vehicle.markInAuction();
        vehicle.markAsSold();
        assertThrows(IllegalStateException.class, () -> vehicle.markNeedsRediagnostic());
    }

    @Test
    void markNeedsRediagnostic_WhenNeedsFixes_ShouldThrowException() {
        vehicle.submitForReview();
        vehicle.reject(adminId, adminName, "Не хватает фотографий");
        assertThrows(IllegalStateException.class, () -> vehicle.markNeedsRediagnostic());
    }

    @Test
    void setLicensePlate_ShouldUpdateField() {
        // given
        String licensePlate = "A123BC";

        // when
        vehicle.setLicensePlate(licensePlate);

        // then
        assertEquals(licensePlate, vehicle.getLicensePlate());
    }

    @Test
    void setColor_ShouldUpdateField() {
        // given
        String color = "black";

        // when
        vehicle.setColor(color);

        // then
        assertEquals(color, vehicle.getColor());
    }

    @Test
    void setMileage_ShouldUpdateField() {
        // given
        Integer mileage = 10000;

        // when
        vehicle.setMileage(mileage);

        // then
        assertEquals(mileage, vehicle.getMileage());
    }

    @Test
    void setEngineCapacity_ShouldUpdateField() {
        // given
        Double engineCapacity = 4.4;

        // when
        vehicle.setEngineCapacity(engineCapacity);

        // then
        assertEquals(engineCapacity, vehicle.getEngineCapacity());
    }

    @Test
    void setFuelType_ShouldUpdateField() {
        // given
        FuelType fuelType = FuelType.PETROL;

        // when
        vehicle.setFuelType(fuelType);

        // then
        assertEquals(fuelType, vehicle.getFuelType());
    }

    @Test
    void setTransmission_ShouldUpdateField() {
        // given
        String transmission = "auto";

        // when
        vehicle.setTransmission(transmission);

        // then
        assertEquals(transmission, vehicle.getTransmission());
    }

    @Test
    void setDescription_ShouldUpdateField() {
        // given
        String description = "Test description";

        // when
        vehicle.setDescription(description);

        // then
        assertEquals(description, vehicle.getDescription());
    }
}