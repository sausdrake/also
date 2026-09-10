package com.example.autoauction.sale.application;

import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.PaymentRefundEvidence;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentRefundEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Slice 1: {@link PaymentRefundService} derives every business field of the
 * {@link PaymentRefundEvidence} from the loaded {@link PaymentEvidence} - it never accepts an
 * amount / buyer / ids as input, so {@code refundedAmount == PaymentEvidence.amount} by
 * construction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRefundService (Slice 1)")
class PaymentRefundServiceTest {

    @Mock
    private PaymentRefundEvidenceRepository paymentRefundEvidenceRepository;

    @Mock
    private PaymentEvidenceRepository paymentEvidenceRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private PaymentRefundService service;

    private final Long auctionId = 1L;
    private final Long buyerId = 100L;
    private final Long paymentEvidenceId = 55L;
    private final BigDecimal paidAmount = new BigDecimal("1020000.00");
    private PaymentEvidence payment;

    @BeforeEach
    void setUp() {
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        lenient().when(clock.instant()).thenReturn(fixed);
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        OffsetDateTime now = OffsetDateTime.ofInstant(fixed, ZoneId.of("UTC"));
        payment = new PaymentEvidence(paymentEvidenceId, auctionId, buyerId, paidAmount, 1L, now, now);
    }

    @Test
    @DisplayName("Всё выводится из PaymentEvidence: paymentEvidenceId, auctionId, buyerId, refundedAmount")
    void recordRefund_DerivesEveryFieldFromPaymentEvidence() {
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(payment));
        when(paymentRefundEvidenceRepository.existsByPaymentEvidenceId(paymentEvidenceId)).thenReturn(false);
        when(paymentRefundEvidenceRepository.save(any(PaymentRefundEvidence.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentRefundEvidence result = service.recordRefund(auctionId, 7L);

        ArgumentCaptor<PaymentRefundEvidence> captor = ArgumentCaptor.forClass(PaymentRefundEvidence.class);
        verify(paymentRefundEvidenceRepository).save(captor.capture());
        PaymentRefundEvidence saved = captor.getValue();
        assertThat(saved.getPaymentEvidenceId()).isEqualTo(paymentEvidenceId);
        assertThat(saved.getAuctionId()).isEqualTo(auctionId);
        assertThat(saved.getBuyerId()).isEqualTo(buyerId);
        assertThat(saved.getRefundedAmount()).isEqualByComparingTo(paidAmount);
        assertThat(saved.getRefundedByAdminId()).isEqualTo(7L);
        assertThat(result.getRefundedAmount()).isEqualByComparingTo(paidAmount);
    }

    @Test
    @DisplayName("Отсутствие PaymentEvidence -> IllegalStateException, ничего не сохраняется")
    void recordRefund_NoPaymentEvidence_Throws() {
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordRefund(auctionId, 7L))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(paymentRefundEvidenceRepository);
    }

    @Test
    @DisplayName("Уже существующая refund-запись отклоняется (pre-check)")
    void recordRefund_DuplicatePreCheck_Throws() {
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(payment));
        when(paymentRefundEvidenceRepository.existsByPaymentEvidenceId(paymentEvidenceId)).thenReturn(true);

        assertThatThrownBy(() -> service.recordRefund(auctionId, 7L))
                .isInstanceOf(IllegalStateException.class);
        verify(paymentRefundEvidenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Гонка на UNIQUE-ограничении (DataIntegrityViolationException) -> IllegalStateException")
    void recordRefund_ConcurrentDuplicateRace_TranslatesToIllegalState() {
        when(paymentEvidenceRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(payment));
        when(paymentRefundEvidenceRepository.existsByPaymentEvidenceId(paymentEvidenceId)).thenReturn(false);
        when(paymentRefundEvidenceRepository.save(any(PaymentRefundEvidence.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.recordRefund(auctionId, 7L))
                .isInstanceOf(IllegalStateException.class);
    }
}
