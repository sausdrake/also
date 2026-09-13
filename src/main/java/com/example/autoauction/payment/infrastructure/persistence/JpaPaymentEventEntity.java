package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Slice 2: JPA representation of {@code payment_event} (V42). Append-only - written once, never
 * updated or deleted.
 */
@Entity
@Table(name = "payment_event")
public class JpaPaymentEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private PaymentEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 24)
    private PaymentEventSource source;

    @Column(name = "raw_payload", length = 100_000)
    private String rawPayload;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected JpaPaymentEventEntity() {
    }

    public JpaPaymentEventEntity(Long id, Long paymentId, PaymentEventType type, PaymentEventSource source,
                                 String rawPayload, OffsetDateTime occurredAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.type = type;
        this.source = source;
        this.rawPayload = rawPayload;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public PaymentEventType getType() { return type; }
    public void setType(PaymentEventType type) { this.type = type; }
    public PaymentEventSource getSource() { return source; }
    public void setSource(PaymentEventSource source) { this.source = source; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }
}
