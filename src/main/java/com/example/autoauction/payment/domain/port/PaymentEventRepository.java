package com.example.autoauction.payment.domain.port;

import com.example.autoauction.payment.domain.PaymentEvent;

import java.util.List;

/**
 * Slice 2: append-only persistence port for {@link PaymentEvent}. There is deliberately no
 * update or delete operation.
 */
public interface PaymentEventRepository {

    PaymentEvent append(PaymentEvent event);

    List<PaymentEvent> findByPaymentId(Long paymentId);
}
