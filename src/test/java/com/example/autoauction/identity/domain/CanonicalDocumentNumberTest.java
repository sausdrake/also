package com.example.autoauction.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 §15 domain tests: canonicalization edge cases. {@link CanonicalDocumentNumber} is the
 * sole canonicalization boundary (Phase 3.1 §4/§5) - every rule below mirrors its own Javadoc
 * exactly, including the deliberate non-goals (no transliteration, no confusable mapping, no
 * exact-format regex).
 */
@DisplayName("CanonicalDocumentNumber - canonicalization edge cases")
class CanonicalDocumentNumberTest {

    @Test
    @DisplayName("trim + internal whitespace removed + ASCII uppercased")
    void from_TrimsWhitespaceAndUppercasesAscii() {
        assertThat(CanonicalDocumentNumber.from("  ab 12 cd 34  ").value()).isEqualTo("AB12CD34");
    }

    @Test
    @DisplayName("MRZ filler '<' stripped")
    void from_StripsMrzFillerCharacter() {
        assertThat(CanonicalDocumentNumber.from("AB1234<<<567").value()).isEqualTo("AB1234567");
    }

    @Test
    @DisplayName("non-Latin script left untouched by the ASCII-only uppercase step")
    void from_NeverUppercasesNonAsciiScript() {
        // Cyrillic lowercase must pass through unchanged - never blanket Unicode-uppercased.
        assertThat(CanonicalDocumentNumber.from("abАБ1234").value()).isEqualTo("ABАБ1234");
    }

    @Test
    @DisplayName("two different raw inputs that canonicalize identically are equal")
    void from_EquivalentRawInputsProduceEqualValue() {
        CanonicalDocumentNumber a = CanonicalDocumentNumber.from(" AB 1234 ");
        CanonicalDocumentNumber b = CanonicalDocumentNumber.from("ab1234");
        assertThat(a).isEqualTo(b);
        assertThat(a.value()).isEqualTo(b.value());
    }

    @Test
    @DisplayName("null raw input rejected")
    void from_NullRejected() {
        assertThatThrownBy(() -> CanonicalDocumentNumber.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank raw input rejected")
    void from_BlankRejected() {
        assertThatThrownBy(() -> CanonicalDocumentNumber.from("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("below MIN_LENGTH (4) after canonicalization rejected")
    void from_TooShortRejected() {
        assertThatThrownBy(() -> CanonicalDocumentNumber.from("AB1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exactly MIN_LENGTH (4) accepted")
    void from_ExactlyMinLengthAccepted() {
        assertThat(CanonicalDocumentNumber.from("AB12").value()).isEqualTo("AB12");
    }

    @Test
    @DisplayName("above MAX_LENGTH (32) after canonicalization rejected")
    void from_TooLongRejected() {
        String raw = "A".repeat(33);
        assertThatThrownBy(() -> CanonicalDocumentNumber.from(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exactly MAX_LENGTH (32) accepted")
    void from_ExactlyMaxLengthAccepted() {
        String raw = "A".repeat(32);
        assertThat(CanonicalDocumentNumber.from(raw).value()).hasSize(32);
    }

    @Test
    @DisplayName("toString() never exposes the canonical value")
    void toString_NeverExposesValue() {
        CanonicalDocumentNumber n = CanonicalDocumentNumber.from("SECRET1234");
        assertThat(n.toString()).doesNotContain("SECRET1234").isEqualTo("CanonicalDocumentNumber[redacted]");
    }

    @Test
    @DisplayName("no raw-value getter exists - value() only ever returns the canonical form")
    void value_ReturnsOnlyCanonicalForm() {
        CanonicalDocumentNumber n = CanonicalDocumentNumber.from("  ab-cd 99  ");
        assertThat(n.value()).isEqualTo("AB-CD99");
    }
}
