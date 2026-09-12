package com.example.autoauction.identity.domain;

/**
 * Phase 3/3.1/4 §4: the sole canonicalization boundary for document numbers.
 *
 * <p>Deliberately conservative - trim, remove internal whitespace, uppercase ASCII only (never a
 * blanket Unicode uppercase, which could unpredictably affect non-Latin scripts), strip the MRZ
 * {@code '<'} filler character. Never transliterates, never performs confusable-character
 * mapping, never invents an exact length/format regex - only a permissive bounded-length sanity
 * check, per the locked decision that the exact Kyrgyz document-number format is unconfirmed and
 * must not be guessed. A false negative (two representations of the same real document failing
 * to match) is safe - caught by manual admin review. A false positive (wrongly merging two
 * distinct documents) would corrupt the core uniqueness invariant and must never happen - hence
 * the conservative-only transformation set.
 *
 * <p>The raw input is intentionally NOT retained anywhere on this object - only {@link #value()}
 * (the canonical form) is ever exposed, matching the locked PII-minimization decision (Phase 3.1
 * §5): the raw value must exist only transiently during request processing, never persisted,
 * never logged.
 */
public final class CanonicalDocumentNumber {

    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 32;

    private final String value;

    private CanonicalDocumentNumber(String value) {
        this.value = value;
    }

    /**
     * Canonicalizes {@code raw} and validates its bounded length. Throws
     * {@link IllegalArgumentException} for null/blank input or a length outside the permissive
     * bound - never a strict format regex, per the locked "do not invent the exact format" rule.
     */
    public static CanonicalDocumentNumber from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Номер документа обязателен");
        }
        String withoutWhitespace = raw.trim().replaceAll("\\s+", "");
        String uppercasedAsciiOnly = uppercaseAsciiOnly(withoutWhitespace);
        String canonical = uppercasedAsciiOnly.replace("<", "");
        if (canonical.length() < MIN_LENGTH || canonical.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Номер документа имеет недопустимую длину");
        }
        return new CanonicalDocumentNumber(canonical);
    }

    /** Uppercases only ASCII a-z; every other character (including any non-Latin script) is left untouched. */
    private static String uppercaseAsciiOnly(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append((char) (c - ('a' - 'A')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CanonicalDocumentNumber other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Never overridden to return {@link #value} - deliberately does not implement a convenience
     * {@code toString()} exposing the canonical value, so accidental string-interpolation into a
     * log statement (e.g. inside a collection/array toString) does not silently leak it.
     */
    @Override
    public String toString() {
        return "CanonicalDocumentNumber[redacted]";
    }
}
