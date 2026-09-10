package com.example.autoauction.sale.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.domain.PaymentEvidence;
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
 * X1, updated for Slice 1 (Sale Completion &amp; Failure API): recordPayment no longer accepts a
 * caller-supplied buyerId (derived from Auction.winnerId) and now fetches the Auction with a
 * pessimistic write lock (findByIdWithLock) inside its own transaction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentEvidenceService (X1 / Slice 1)")
class PaymentEvidenceServiceTest {

    @Mock
    private PaymentEvidenceRepository paymentEvidenceRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private PaymentEvidenceService service;

    private final Long auctionId = 1L;
    private final Long buyerId = 100L;
    private final Long receivedByAdminId = 1L;
    private final BigDecimal winningBid = new BigDecimal("1020000.00");
    private Auction auction;

    @BeforeEach
    void setUp() {
        Instant fixedInstant = Instant.parse("2026-01-01T00:00:00Z");
        OffsetDateTime now = OffsetDateTime.ofInstant(fixedInstant, ZoneId.of("UTC"));
        lenient().when(clock.instant()).thenReturn(fixedInstant);
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

        auction = new Auction(5L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusDays(7), now.minusHours(1), 10L);
        auction.setId(auctionId);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setWinnerId(buyerId);
        auction.setWinningBid(winningBid);
    }

    @Test
    @DisplayName("Несуществующий аукцион отклоняется")
    void recordPayment_AuctionNotFound_ThrowsIllegalArgumentException() {
        when(auctionRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordPayment(999L, winningBid, receivedByAdminId))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentEvidenceRepository);
    }

    @Test
    @DisplayName("Аукцион не в WAITING_FOR_PAYMENT отклоняется")
    void recordPayment_AuctionNotWaitingForPayment_ThrowsIllegalStateException() {
        auction.setStatus(AuctionStatus.ACTIVE);
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.recordPayment(auctionId, winningBid, receivedByAdminId))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(paymentEvidenceRepository);
    }

    @Test
    @DisplayName("Аукцион без победителя отклоняется")
    void recordPayment_NoWinner_ThrowsIllegalStateException() {
        auction.setWinnerId(null);
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.recordPayment(auctionId, winningBid, receivedByAdminId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("amount не совпадает с winningBid отклоняется")
    void recordPayment_AmountMismatch_ThrowsIllegalArgumentException() {
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.recordPayment(auctionId, new BigDecimal("999999"), receivedByAdminId))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentEvidenceRepository);
    }

    @Test
    @DisplayName("Валидный платёж сохраняется; buyerId выводится из победителя аукциона")
    void recordPayment_ValidInput_Persists() {
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(paymentEvidenceRepository.save(any(PaymentEvidence.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentEvidence result = service.recordPayment(auctionId, winningBid, receivedByAdminId);

        assertThat(result.getAuctionId()).isEqualTo(auctionId);
        assertThat(result.getBuyerId()).isEqualTo(buyerId);
        assertThat(result.getAmount()).isEqualByComparingTo(winningBid);
        verify(paymentEvidenceRepository, times(1)).save(any(PaymentEvidence.class));
    }

    @Test
    @DisplayName("Дубликат (existsByAuctionId=true) отклоняется")
    void recordPayment_DuplicatePreCheck_ThrowsIllegalStateException() {
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(true);

        assertThatThrownBy(() -> service.recordPayment(auctionId, winningBid, receivedByAdminId))
                .isInstanceOf(IllegalStateException.class);
        verify(paymentEvidenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Гонка на UNIQUE-ограничении (DataIntegrityViolationException) переводится в явную ошибку")
    void recordPayment_ConcurrentDuplicateRace_TranslatesToIllegalStateException() {
        when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auction));
        when(paymentEvidenceRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(paymentEvidenceRepository.save(any(PaymentEvidence.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.recordPayment(auctionId, winningBid, receivedByAdminId))
                .isInstanceOf(IllegalStateException.class);
    }
}
