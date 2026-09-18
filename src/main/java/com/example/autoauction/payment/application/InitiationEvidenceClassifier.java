package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.PaymentEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Phase 35: the single, pure source of truth for parsing/validating {@code INITIATION_ACCEPTED}
 * evidence - used identically by the real recovery path ({@link
 * PaymentCreationOrchestrator#recoverFromInitiationEvidence}) and by the read-only admin
 * observability path ({@code AdminPaymentService#listStuckInitiations}), so the two can never
 * disagree on what a given set of evidence means.
 *
 * <p>Deliberately pure: no DB access, no logging, no transactions, no mutation of any kind - it
 * is a plain function from {@code List<PaymentEvent>} to {@link InitiationEvidenceClassification}.
 */
public final class InitiationEvidenceClassifier {

    private static final String PREFIX = "providerReference=";

    private InitiationEvidenceClassifier() {
    }

    /**
     * @param evidence every {@code INITIATION_ACCEPTED} {@link PaymentEvent} for one payment, in
     *                 any order - the result does not depend on ordering.
     */
    public static InitiationEvidenceClassification classify(List<PaymentEvent> evidence) {
        if (evidence.isEmpty()) {
            return new InitiationEvidenceClassification.None();
        }

        List<String> extracted = new ArrayList<>(evidence.size());
        for (PaymentEvent event : evidence) {
            String value = extractProviderReference(event);
            if (value == null) {
                return new InitiationEvidenceClassification.Malformed(malformedReason(event));
            }
            extracted.add(value);
        }

        String first = extracted.get(0);
        boolean allAgree = extracted.stream().allMatch(v -> v.equals(first));
        if (!allAgree) {
            return new InitiationEvidenceClassification.Conflicting(List.copyOf(new LinkedHashSet<>(extracted)));
        }
        return new InitiationEvidenceClassification.Valid(first);
    }

    /** Returns the extracted value, or {@code null} if the payload is structurally invalid
     * (null/missing prefix, empty, or whitespace-only after the prefix). */
    private static String extractProviderReference(PaymentEvent event) {
        String payload = event.getRawPayload();
        if (payload == null || !payload.startsWith(PREFIX) || payload.length() <= PREFIX.length()) {
            return null;
        }
        String value = payload.substring(PREFIX.length());
        return value.isBlank() ? null : value;
    }

    private static String malformedReason(PaymentEvent event) {
        return "PaymentEvent " + event.getId() + " содержит некорректные данные инициации: " + event.getRawPayload();
    }
}
