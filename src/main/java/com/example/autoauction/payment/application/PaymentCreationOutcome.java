package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.Payment;

/**
 * P1.5: result of {@link PaymentCreationService#createPayment}. {@code isNewCreation} tells the
 * orchestrator whether provider initiation must run ({@code true}) or must be skipped entirely
 * because this is an idempotent replay of an already-existing Payment ({@code false}) - a replay
 * must never trigger a second provider call for the same logical request.
 */
public record PaymentCreationOutcome(Payment payment, boolean isNewCreation) {
}
