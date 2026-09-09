package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * X3-C: unit coverage for AuctionCommitmentResolver's 7-step resolution algorithm, per the
 * architecture re-lock's required test matrix (items 4-12).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты AuctionCommitmentResolver")
class AuctionCommitmentResolverTest {

    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private DiagnosticRequestRepository diagnosticRequestRepository;
    @Mock
    private SellerDiagnosticCommitmentRepository commitmentRepository;
    @Mock
    private AuctionRepository auctionRepository;

    private AuctionCommitmentResolver resolver;

    private static final Long VEHICLE_ID = 1L;
    private static final Long DIAGNOSTIC_REQUEST_ID = 10L;
    private static final Long COMMITMENT_ID = 100L;

    private Vehicle vehicleWithDiagnosticRequest;
    private DiagnosticRequest diagnosticRequest;
    private SellerDiagnosticCommitment commitment;

    @BeforeEach
    void setUp() {
        resolver = new AuctionCommitmentResolver(
                vehicleRepository, diagnosticRequestRepository, commitmentRepository, auctionRepository);

        vehicleWithDiagnosticRequest = new Vehicle("BMW", "M5", 2023, "WBSDE91070CZ12345",
                VehicleType.SEDAN, 16L, "diagnostic");
        vehicleWithDiagnosticRequest.setId(VEHICLE_ID);
        vehicleWithDiagnosticRequest.setDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID);

        diagnosticRequest = new DiagnosticRequest();
        diagnosticRequest.setId(DIAGNOSTIC_REQUEST_ID);

        commitment = new SellerDiagnosticCommitment();
        commitment.setId(COMMITMENT_ID);
        commitment.setDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID);
        commitment.setStatus(CommitmentStatus.AWAITING_FINALIZATION);
    }

    @Nested
    @DisplayName("Happy path")
    class SuccessTests {

        @Test
        @DisplayName("Test 9: AWAITING_FINALIZATION commitment, не привязан -> возвращает commitment id")
        void resolveEligibleCommitment_Success_ReturnsCommitmentId() {
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicleWithDiagnosticRequest));
            when(diagnosticRequestRepository.findById(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(diagnosticRequest));
            when(commitmentRepository.findByDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(commitment));
            when(auctionRepository.existsBySellerDiagnosticCommitmentId(COMMITMENT_ID)).thenReturn(false);

            Long result = resolver.resolveEligibleCommitment(VEHICLE_ID);

            assertEquals(COMMITMENT_ID, result);
        }

        @Test
        @DisplayName("Test 11/12: resolver не выполняет ни одной write-операции")
        void resolveEligibleCommitment_NeverWritesAnything() {
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicleWithDiagnosticRequest));
            when(diagnosticRequestRepository.findById(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(diagnosticRequest));
            when(commitmentRepository.findByDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(commitment));
            when(auctionRepository.existsBySellerDiagnosticCommitmentId(COMMITMENT_ID)).thenReturn(false);

            resolver.resolveEligibleCommitment(VEHICLE_ID);

            verify(vehicleRepository, never()).save(any());
            verify(commitmentRepository, never()).save(any());
            verify(commitmentRepository, never()).markAwaitingFinalization(any());
            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
            verify(auctionRepository, never()).save(any());
            assertEquals(CommitmentStatus.AWAITING_FINALIZATION, commitment.getStatus());
        }
    }

    @Nested
    @DisplayName("Rejection paths")
    class RejectionTests {

        @Test
        @DisplayName("Test 4: Vehicle без diagnosticRequestId -> IllegalArgumentException")
        void resolveEligibleCommitment_NoDiagnosticRequestId_Rejects() {
            Vehicle bare = new Vehicle("Lada", "Vesta", 2022, "XTA21230010123456",
                    VehicleType.SEDAN, 16L, "diagnostic");
            bare.setId(VEHICLE_ID);
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(bare));

            assertThrows(IllegalArgumentException.class, () -> resolver.resolveEligibleCommitment(VEHICLE_ID));

            verifyNoInteractions(diagnosticRequestRepository, commitmentRepository);
        }

        @Test
        @DisplayName("Vehicle не найден -> IllegalArgumentException")
        void resolveEligibleCommitment_VehicleNotFound_Rejects() {
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> resolver.resolveEligibleCommitment(VEHICLE_ID));
        }

        @Test
        @DisplayName("Test 5: DiagnosticRequest отсутствует (corrupted data) -> IllegalStateException")
        void resolveEligibleCommitment_MissingDiagnosticRequest_Rejects() {
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicleWithDiagnosticRequest));
            when(diagnosticRequestRepository.findById(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class, () -> resolver.resolveEligibleCommitment(VEHICLE_ID));

            verifyNoInteractions(commitmentRepository);
        }

        @Test
        @DisplayName("Test 6: Commitment отсутствует -> IllegalArgumentException")
        void resolveEligibleCommitment_MissingCommitment_Rejects() {
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicleWithDiagnosticRequest));
            when(diagnosticRequestRepository.findById(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(diagnosticRequest));
            when(commitmentRepository.findByDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> resolver.resolveEligibleCommitment(VEHICLE_ID));
        }

        @Test
        @DisplayName("Test 7: Commitment FROZEN -> IllegalStateException")
        void resolveEligibleCommitment_CommitmentFrozen_Rejects() {
            commitment.setStatus(CommitmentStatus.FROZEN);
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicleWithDiagnosticRequest));
            when(diagnosticRequestRepository.findById(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(diagnosticRequest));
            when(commitmentRepository.findByDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(commitment));

            assertThrows(IllegalStateException.class, () -> resolver.resolveEligibleCommitment(VEHICLE_ID));

            verify(auctionRepository, never()).existsBySellerDiagnosticCommitmentId(any());
        }

        @Test
        @DisplayName("Test 8: Commitment FINALIZED -> IllegalStateException")
        void resolveEligibleCommitment_CommitmentFinalized_Rejects() {
            commitment.setStatus(CommitmentStatus.FINALIZED);
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicleWithDiagnosticRequest));
            when(diagnosticRequestRepository.findById(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(diagnosticRequest));
            when(commitmentRepository.findByDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(commitment));

            assertThrows(IllegalStateException.class, () -> resolver.resolveEligibleCommitment(VEHICLE_ID));
        }

        @Test
        @DisplayName("Test 10: Commitment уже привязан к другому Auction -> IllegalStateException")
        void resolveEligibleCommitment_CommitmentAlreadyLinked_Rejects() {
            when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicleWithDiagnosticRequest));
            when(diagnosticRequestRepository.findById(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(diagnosticRequest));
            when(commitmentRepository.findByDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID)).thenReturn(Optional.of(commitment));
            when(auctionRepository.existsBySellerDiagnosticCommitmentId(COMMITMENT_ID)).thenReturn(true);

            assertThrows(IllegalStateException.class, () -> resolver.resolveEligibleCommitment(VEHICLE_ID));
        }
    }
}
