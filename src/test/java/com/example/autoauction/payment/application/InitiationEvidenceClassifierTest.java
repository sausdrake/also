package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 35: pure unit coverage of {@link InitiationEvidenceClassifier} - no Spring context, no
 * database, no I/O of any kind. Every scenario constructs {@link PaymentEvent} directly; no
 * fixture ever touches a repository.
 */
@DisplayName("Phase 35: InitiationEvidenceClassifier")
class InitiationEvidenceClassifierTest {

    private static PaymentEvent event(Long id, String rawPayload) {
        return new PaymentEvent(id, 1L, PaymentEventType.INITIATION_ACCEPTED, PaymentEventSource.CLIENT,
                rawPayload, OffsetDateTime.now());
    }

    @Test
    @DisplayName("no evidence -> None")
    void noEvidence_None() {
        assertThat(InitiationEvidenceClassifier.classify(List.of()))
                .isInstanceOf(InitiationEvidenceClassification.None.class);
    }

    @Test
    @DisplayName("single well-formed evidence -> Valid")
    void singleValid_Valid() {
        var result = InitiationEvidenceClassifier.classify(List.of(event(1L, "providerReference=PSP-ABC")));
        assertThat(result).isInstanceOf(InitiationEvidenceClassification.Valid.class);
        assertThat(((InitiationEvidenceClassification.Valid) result).providerReference()).isEqualTo("PSP-ABC");
    }

    @Test
    @DisplayName("agreeing duplicate evidence -> Valid")
    void agreeingDuplicates_Valid() {
        var result = InitiationEvidenceClassifier.classify(List.of(
                event(1L, "providerReference=PSP-SAME"),
                event(2L, "providerReference=PSP-SAME")));
        assertThat(result).isInstanceOf(InitiationEvidenceClassification.Valid.class);
        assertThat(((InitiationEvidenceClassification.Valid) result).providerReference()).isEqualTo("PSP-SAME");
    }

    @Test
    @DisplayName("null payload -> Malformed")
    void nullPayload_Malformed() {
        assertThat(InitiationEvidenceClassifier.classify(List.of(event(1L, null))))
                .isInstanceOf(InitiationEvidenceClassification.Malformed.class);
    }

    @Test
    @DisplayName("missing prefix -> Malformed")
    void missingPrefix_Malformed() {
        assertThat(InitiationEvidenceClassifier.classify(List.of(event(1L, "PSP-NO-PREFIX"))))
                .isInstanceOf(InitiationEvidenceClassification.Malformed.class);
    }

    @Test
    @DisplayName("empty reference after prefix -> Malformed")
    void emptyReference_Malformed() {
        assertThat(InitiationEvidenceClassifier.classify(List.of(event(1L, "providerReference="))))
                .isInstanceOf(InitiationEvidenceClassification.Malformed.class);
    }

    @Test
    @DisplayName("whitespace-only reference after prefix -> Malformed (before finalizeAccepted would ever see it)")
    void whitespaceOnlyReference_Malformed() {
        assertThat(InitiationEvidenceClassifier.classify(List.of(event(1L, "providerReference=   "))))
                .isInstanceOf(InitiationEvidenceClassification.Malformed.class);
    }

    @Test
    @DisplayName("embedded '=' in the reference is preserved exactly, not treated as a delimiter")
    void embeddedEquals_PreservedExactly() {
        var result = InitiationEvidenceClassifier.classify(List.of(event(1L, "providerReference=A=B")));
        assertThat(result).isInstanceOf(InitiationEvidenceClassification.Valid.class);
        assertThat(((InitiationEvidenceClassification.Valid) result).providerReference()).isEqualTo("A=B");
    }

    @Test
    @DisplayName("two disagreeing references -> Conflicting")
    void disagreeingReferences_Conflicting() {
        var result = InitiationEvidenceClassifier.classify(List.of(
                event(1L, "providerReference=PSP-A"),
                event(2L, "providerReference=PSP-B")));
        assertThat(result).isInstanceOf(InitiationEvidenceClassification.Conflicting.class);
        assertThat(((InitiationEvidenceClassification.Conflicting) result).providerReferences())
                .containsExactly("PSP-A", "PSP-B");
    }

    @Test
    @DisplayName("one malformed entry among otherwise-agreeing entries -> Malformed, not Conflicting")
    void oneMalformedAmongValid_Malformed() {
        var result = InitiationEvidenceClassifier.classify(List.of(
                event(1L, "providerReference=PSP-A"),
                event(2L, null)));
        assertThat(result).isInstanceOf(InitiationEvidenceClassification.Malformed.class);
    }
}
