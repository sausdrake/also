package com.example.autoauction.payment.domain.port;

import com.example.autoauction.payment.domain.PaymentEvent;

import java.util.List;
import java.util.Optional;

/**
 * Slice 2: append-only persistence port for {@link PaymentEvent}. There is deliberately no
 * update or delete operation.
 */
public interface PaymentEventRepository {

    PaymentEvent append(PaymentEvent event);

    List<PaymentEvent> findByPaymentId(Long paymentId);

    /**
     * Phase 31: the single most-recently-occurred event for this payment, ordered by {@code
     * occurredAt DESC} with {@code id DESC} as a deterministic tiebreak for coincident
     * timestamps - used only to decide whether a new {@code POLL_RESULT} would be a consecutive
     * duplicate (see {@code PaymentConfirmationService}). Deliberately narrow: does not load the
     * full event history the way {@link #findByPaymentId} does.
     */
    Optional<PaymentEvent> findLatestByPaymentId(Long paymentId);
}
