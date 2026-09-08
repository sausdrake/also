package com.example.autoauction.sale.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("SaleResolutionService (X1)")
class SaleResolutionServiceTest {

    @Mock
    private SaleResolutionRepository saleResolutionRepository;

    @Mock
    private PaymentEvidenceRepository paymentEvidenceRepository;

    @Mock
    private HandoverEvidenceRepository handoverEvidenceRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private SaleResolutionService service;

    private final Long auctionId = 1L;
    private final Long vehicleId = 5L;
    private final Long buyerId = 100L;
    private final Long resolvedBy = 1L;
    private final BigDecimal winningBid = new BigDecimal("1020000.00");
    private Auction auction;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        Instant fixedInstant = Instant.parse("2026-01-01T00:00:00Z");
        now = OffsetDateTime.ofInstant(fixedInstant, ZoneId.of("UTC"));
        lenient().when(clock.instant()).thenReturn(fixedInstant);
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

        auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusDays(7), now.minusHours(1), 10L);
        auction.setId(auctionId);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setWinnerId(buyerId);
        auction.setWinningBid(winningBid);
    }

    private PaymentEvidence validPayment() {
        return new PaymentEvidence(10L, auctionId, buyerId, winningBid, 1L, now, now);
    }

    private HandoverEvidence validHandover() {
        return new HandoverEvidence(20L, auctionId, vehicleId, buyerId, 1L, now, now);
    }

    @Test
    @DisplayName("Несуществующий аукцион отклоняется")
    void createSuccess_AuctionNotFound_ThrowsIllegalArgumentException() {
        when(auctionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSuccess(999L, resolvedBy))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(saleResolutionRepository);
    }

    @Test
    @DisplayName("Отсутствующий PaymentEvidence отклоняется")
    void createSuccess_MissingPaymentEvidence_ThrowsIllegalStateException() {
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSuccess(auctionId, resolvedBy))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(saleResolutionRepository);
    }

    @Test
    @DisplayName("Отсутствующий HandoverEvidence отклоняется")
    void createSuccess_MissingHandoverEvidence_ThrowsIllegalStateException() {
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validPayment()));
        when(handoverEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSuccess(auctionId, resolvedBy))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(saleResolutionRepository);
    }

    @Test
    @DisplayName("PaymentEvidence, принадлежащий другому аукциону, отклоняется")
    void createSuccess_PaymentEvidenceWrongAuction_ThrowsIllegalStateException() {
        PaymentEvidence wrongAuctionPayment = new PaymentEvidence(10L, 999L, buyerId, winningBid, 1L, now, now);
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(wrongAuctionPayment));
        when(handoverEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validHandover()));

        assertThatThrownBy(() -> service.createSuccess(auctionId, resolvedBy))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(saleResolutionRepository);
    }

    @Test
    @DisplayName("HandoverEvidence, принадлежащий другому аукциону, отклоняется")
    void createSuccess_HandoverEvidenceWrongAuction_ThrowsIllegalStateException() {
        HandoverEvidence wrongAuctionHandover = new HandoverEvidence(20L, 999L, vehicleId, buyerId, 1L, now, now);
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validPayment()));
        when(handoverEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(wrongAuctionHandover));

        assertThatThrownBy(() -> service.createSuccess(auctionId, resolvedBy))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PaymentEvidence.buyerId, не совпадающий с победителем, отклоняется")
    void createSuccess_PaymentBuyerMismatch_ThrowsIllegalStateException() {
        PaymentEvidence wrongBuyerPayment = new PaymentEvidence(10L, auctionId, 999L, winningBid, 1L, now, now);
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(wrongBuyerPayment));
        when(handoverEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validHandover()));

        assertThatThrownBy(() -> service.createSuccess(auctionId, resolvedBy))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("HandoverEvidence.vehicleId, не совпадающий с vehicleId аукциона, отклоняется")
    void createSuccess_HandoverVehicleMismatch_ThrowsIllegalStateException() {
        HandoverEvidence wrongVehicleHandover = new HandoverEvidence(20L, auctionId, 999L, buyerId, 1L, now, now);
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validPayment()));
        when(handoverEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(wrongVehicleHandover));

        assertThatThrownBy(() -> service.createSuccess(auctionId, resolvedBy))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("SUCCESS с корректными evidence сохраняется")
    void createSuccess_ValidEvidence_Persists() {
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validPayment()));
        when(handoverEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validHandover()));
        when(saleResolutionRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(saleResolutionRepository.save(any(SaleResolution.class))).thenAnswer(inv -> inv.getArgument(0));

        SaleResolution result = service.createSuccess(auctionId, resolvedBy);

        assertThat(result.getAuctionId()).isEqualTo(auctionId);
        assertThat(result.getPaymentEvidenceId()).isEqualTo(10L);
        assertThat(result.getHandoverEvidenceId()).isEqualTo(20L);
        verify(saleResolutionRepository, times(1)).save(any(SaleResolution.class));
    }

    @Test
    @DisplayName("Дубликат SaleResolution отклоняется")
    void createSuccess_Duplicate_ThrowsIllegalStateException() {
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validPayment()));
        when(handoverEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validHandover()));
        when(saleResolutionRepository.existsByAuctionId(auctionId)).thenReturn(true);

        assertThatThrownBy(() -> service.createSuccess(auctionId, resolvedBy))
                .isInstanceOf(IllegalStateException.class);
        verify(saleResolutionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Гонка на UNIQUE-ограничении переводится в явную ошибку")
    void createSuccess_ConcurrentDuplicateRace_TranslatesToIllegalStateException() {
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validPayment()));
        when(handoverEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(validHandover()));
        when(saleResolutionRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(saleResolutionRepository.save(any(SaleResolution.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.createSuccess(auctionId, resolvedBy))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markFinancialResolutionDone: успешный claim")
    void markFinancialResolutionDone_ClaimSucceeds_NoException() {
        when(saleResolutionRepository.claimFinancialResolutionDone(1L)).thenReturn(true);
        service.markFinancialResolutionDone(1L);
        verify(saleResolutionRepository).claimFinancialResolutionDone(1L);
    }

    @Test
    @DisplayName("markFinancialResolutionDone: неудачный claim -> исключение")
    void markFinancialResolutionDone_ClaimFails_ThrowsIllegalStateException() {
        when(saleResolutionRepository.claimFinancialResolutionDone(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.markFinancialResolutionDone(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("resetFinancialResolutionToPending: неудачный claim -> исключение")
    void resetFinancialResolutionToPending_ClaimFails_ThrowsIllegalStateException() {
        when(saleResolutionRepository.resetFinancialResolutionToPending(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.resetFinancialResolutionToPending(1L))
                .isInstanceOf(IllegalStateException.class);
    }
}
