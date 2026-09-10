package com.example.autoauction.sale.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * X1, updated for Slice 1: recordHandover no longer accepts caller-supplied vehicleId/buyerId
 * (both derived from the Auction), fetches the Auction under a pessimistic lock, and now
 * requires a pre-existing PaymentEvidence for the same auction (payment-before-handover is an
 * invariant of this creation use case).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HandoverEvidenceService (X1 / Slice 1)")
class HandoverEvidenceServiceTest {

    @Mock
    private HandoverEvidenceRepository handoverEvidenceRepository;

    @Mock
    private PaymentEvidenceRepository paymentEvidenceRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private HandoverEvidenceService service;

    private final Long auctionId = 1L;
    private final Long vehicleId = 5L;
    private final Long buyerId = 100L;
    private final Long confirmedByAdminId = 1L;
    private Auction auction;

    @BeforeEach
    void setUp() {
        Instant fixedInstant = Instant.parse("2026-01-01T00:00:00Z");
        OffsetDateTime now = OffsetDateTime.ofInstant(fixedInstant, ZoneId.of("UTC"));
        lenient().when(clock.instant()).thenReturn(fixedInstant);
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

        auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusDays(7), now.minusHours(1), 10L);
        auction.setId(auctionId);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setWinnerId(buyerId);
        auction.setWinningBid(new BigDecimal("1020000.00"));
    }

    @Test
    @DisplayName("Несуществующий аукцион отклоняется")
    void recordHandover_AuctionNotFound_ThrowsIllegalArgumentException() {
        when(auctionRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordHandover(999L, confirmedByAdminId))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(handoverEvidenceRepository);
    }

    @Test
    @DisplayName("Аукцион не в WAITING_FOR_PAYMENT отклоняется")
    void recordHandover_WrongStatus_ThrowsIllegalStateException() {
        auction.setStatus(AuctionStatus.ACTIVE);
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.recordHandover(auctionId, confirmedByAdminId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Отсутствие PaymentEvidence отклоняется (оплата предшествует передаче)")
    void recordHandover_WithoutPayment_ThrowsIllegalStateException() {
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(false);

        assertThatThrownBy(() -> service.recordHandover(auctionId, confirmedByAdminId))
                .isInstanceOf(IllegalStateException.class);
        verify(handoverEvidenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Валидная передача сохраняется; vehicleId/buyerId выводятся из аукциона")
    void recordHandover_ValidInput_Persists() {
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(true);
        when(handoverEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(handoverEvidenceRepository.save(any(HandoverEvidence.class))).thenAnswer(inv -> inv.getArgument(0));

        HandoverEvidence result = service.recordHandover(auctionId, confirmedByAdminId);

        assertThat(result.getAuctionId()).isEqualTo(auctionId);
        assertThat(result.getVehicleId()).isEqualTo(vehicleId);
        assertThat(result.getBuyerId()).isEqualTo(buyerId);
        verify(handoverEvidenceRepository, times(1)).save(any(HandoverEvidence.class));
    }

    @Test
    @DisplayName("Дубликат отклоняется")
    void recordHandover_Duplicate_ThrowsIllegalStateException() {
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(true);
        when(handoverEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(true);

        assertThatThrownBy(() -> service.recordHandover(auctionId, confirmedByAdminId))
                .isInstanceOf(IllegalStateException.class);
        verify(handoverEvidenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Гонка на UNIQUE-ограничении переводится в явную ошибку")
    void recordHandover_ConcurrentDuplicateRace_TranslatesToIllegalStateException() {
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(true);
        when(handoverEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(handoverEvidenceRepository.save(any(HandoverEvidence.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.recordHandover(auctionId, confirmedByAdminId))
                .isInstanceOf(IllegalStateException.class);
    }
}
