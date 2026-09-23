package com.example.autoauction.auction.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sale-UI Gap #2: GET /api/v1/vehicles/{vehicleId}/auction - owner-or-admin only, ownership
 * resolved transitively via Vehicle.diagnosticRequestId -&gt; DiagnosticRequest.userId (no
 * Vehicle.ownerId field). Real DB, real HTTP layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Sale-UI Gap #2: Vehicle -> Auction lookup (owner-or-admin)")
class VehicleAuctionControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private AuctionRepository auctionRepository;

    private record Fixture(Long vehicleId, Long ownerId, Long auctionId) {}

    private Long newDiagnosticRequest(Long ownerId) {
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(ownerId);
        request.setBrand("Toyota");
        request.setModel("Camry");
        request.setYear(2020);
        request.setVin("VAQ" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(BigDecimal.ZERO);
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        return diagnosticRequestRepository.save(request).getId();
    }

    private Vehicle newVehicleFor(Long diagnosticRequestId) {
        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                "VAQV" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "diag");
        vehicle.setDiagnosticRequestId(diagnosticRequestId);
        vehicle.submitForReview();
        vehicle.approve(1L, "admin");
        return vehicleRepository.save(vehicle);
    }

    /** Vehicle with a linked DiagnosticRequest and an Auction, no vehicle-in-auction transition needed for this read-only lookup. */
    private Fixture withAuction() {
        Long ownerId = 700_000L + (System.nanoTime() % 100_000_000L);
        Long requestId = newDiagnosticRequest(ownerId);
        Vehicle vehicle = newVehicleFor(requestId);
        vehicle.markInAuction();
        Long vehicleId = vehicleRepository.save(vehicle).getId();

        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), now.minusDays(1), now.plusDays(1), 10L);
        auction.setStatus(AuctionStatus.ACTIVE);
        Long auctionId = auctionRepository.save(auction).getId();

        return new Fixture(vehicleId, ownerId, auctionId);
    }

    private static String path(Long vehicleId) {
        return "/api/v1/vehicles/" + vehicleId + "/auction";
    }

    @Test
    @DisplayName("Anonymous -> 401")
    void anonymous_401() throws Exception {
        mockMvc.perform(get(path(1L))).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockCustomUser(userId = 999_001L, roles = {"USER"})
    @DisplayName("Owner (matches DiagnosticRequest.userId) -> 200, correct auction returned")
    void owner_200() throws Exception {
        Long ownerId = 999_001L;
        Long requestId = newDiagnosticRequest(ownerId);
        Vehicle vehicle = newVehicleFor(requestId);
        vehicle.markInAuction();
        Long vehicleId = vehicleRepository.save(vehicle).getId();
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), now.minusDays(1), now.plusDays(1), 10L);
        auction.setStatus(AuctionStatus.ACTIVE);
        Long auctionId = auctionRepository.save(auction).getId();

        mockMvc.perform(get(path(vehicleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(auctionId))
                .andExpect(jsonPath("$.vehicleId").value(vehicleId));
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Admin (not owner) -> 200")
    void admin_200() throws Exception {
        Fixture f = withAuction();
        mockMvc.perform(get(path(f.vehicleId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(f.auctionId()));
    }

    @Test
    @WithMockCustomUser(userId = 999_002L, roles = {"USER"})
    @DisplayName("A different, non-owning USER -> 403")
    void differentUser_403() throws Exception {
        Fixture f = withAuction();
        mockMvc.perform(get(path(f.vehicleId()))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockCustomUser(userId = 999_003L, roles = {"DIAGNOSTIC"})
    @DisplayName("A DIAGNOSTIC who is not the owner and not ADMIN -> 403 (no automatic diagnostician access)")
    void diagnostician_NotOwner_403() throws Exception {
        Fixture f = withAuction();
        mockMvc.perform(get(path(f.vehicleId()))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Unknown vehicleId -> 404")
    void unknownVehicle_404() throws Exception {
        mockMvc.perform(get(path(999_999_999L))).andExpect(status().isNotFound());
    }

    @Test
    @WithMockCustomUser(userId = 999_004L, roles = {"USER"})
    @DisplayName("Owner's vehicle exists but has no auction yet -> 404")
    void ownerVehicle_NoAuctionYet_404() throws Exception {
        Long ownerId = 999_004L;
        Long requestId = newDiagnosticRequest(ownerId);
        Vehicle vehicle = newVehicleFor(requestId);
        Long vehicleId = vehicleRepository.save(vehicle).getId();

        mockMvc.perform(get(path(vehicleId))).andExpect(status().isNotFound());
    }
}
