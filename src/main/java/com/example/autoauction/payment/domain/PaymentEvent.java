package com.example.autoauction.payment.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Slice 2: an append-only audit record for one thing that happened to a {@link Payment} - a
 * lifecycle transition, an inbound provider callback, a status-poll result, or a reconciliation
 * action.
 *
 * <p>Immutable by design: no setters, all fields {@code final} (the surrogate {@code id} is set
 * once by the persistence mapper on reconstruction). The {@code rawPayload} retains the verbatim
 * provider payload for reconciliation/audit and is never exposed to ordinary users.
 */
public class PaymentEvent {

    private Long id;
    private final Long paymentId;
    private final PaymentEventType type;
    private final PaymentEventSource source;
    private final String rawPayload;
    private final OffsetDateTime occurredAt;

    public PaymentEvent(Long paymentId, PaymentEventType type, PaymentEventSource source,
                        String rawPayload, OffsetDateTime occurredAt) {
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId не может быть null");
        this.type = Objects.requireNonNull(type, "type не может быть null");
        this.source = Objects.requireNonNull(source, "source не может быть null");
        this.rawPayload = rawPayload;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt не может быть null");
    }

    /** Reconstruction constructor - persistence mapper only. */
    public PaymentEvent(Long id, Long paymentId, PaymentEventType type, PaymentEventSource source,
                        String rawPayload, OffsetDateTime occurredAt) {
        this(paymentId, type, source, rawPayload, occurredAt);
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public PaymentEventType getType() { return type; }
    public PaymentEventSource getSource() { return source; }
    public String getRawPayload() { return rawPayload; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
