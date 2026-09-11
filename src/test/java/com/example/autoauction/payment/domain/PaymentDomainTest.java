package com.example.autoauction.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Slice 2: Payment aggregate — invariants & guarded transitions")
class PaymentDomainTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-10T10:00:00Z");
    private static final OffsetDateTime EXP = NOW.plusMinutes(30);

    private Payment newIntent() {
        return new Payment(7L, "PAY-abc", new BigDecimal("10000.00"), null, NOW, EXP);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        void validCreation_startsInCreatedKgs() {
            Payment p = newIntent();
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CREATED);
            assertThat(p.getCurrency()).isEqualTo("KGS");
            assertThat(p.getUserId()).isEqualTo(7L);
            assertThat(p.getMerchantReference()).isEqualTo("PAY-abc");
            assertThat(p.getAmountRequested()).isEqualByComparingTo("10000.00");
            assertThat(p.getAmountConfirmed()).isNull();
            assertThat(p.getProviderTransactionId()).isNull();
            assertThat(p.getProviderReference()).isNull();
            assertThat(p.isLateSuccessAfterExpiry()).isFalse();
            assertThat(p.isNonTerminal()).isTrue();
        }

        @Test
        void zeroOrNegativeAmount_rejected() {
            assertThatThrownBy(() -> new Payment(7L, "PAY-1", BigDecimal.ZERO, null, NOW, EXP))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Payment(7L, "PAY-1", new BigDecimal("-1"), null, NOW, EXP))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullOrBlankMerchantReference_rejected() {
            assertThatThrownBy(() -> new Payment(7L, null, BigDecimal.TEN, null, NOW, EXP))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Payment(7L, "  ", BigDecimal.TEN, null, NOW, EXP))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void reconstructionWithNonKgsCurrency_rejected() {
            assertThatThrownBy(() -> new Payment(1L, 7L, "PAY-x", BigDecimal.TEN, null, "USD",
                    PaymentStatus.CREATED, null, null, null, false, NOW, EXP, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("legal transitions")
    class LegalTransitions {

        @Test
        void created_toPending_toSucceeded() {
            Payment p = newIntent();
            p.markPending("PSP-REF-1");
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(p.getProviderReference()).isEqualTo("PSP-REF-1");
            p.markSucceeded("TX-1", new BigDecimal("10000.00"));
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(p.getProviderTransactionId()).isEqualTo("TX-1");
            assertThat(p.getAmountConfirmed()).isEqualByComparingTo("10000.00");
            assertThat(p.isLateSuccessAfterExpiry()).isFalse();
            // providerReference and providerTransactionId are independent identifiers - they are
            // legally allowed to differ, and neither transition overwrites the other.
            assertThat(p.getProviderReference()).isNotEqualTo(p.getProviderTransactionId());
        }

        @Test
        void created_toFailed_toCancelled_toExpired_allAllowed() {
            Payment a = newIntent(); a.markFailed();    assertThat(a.getStatus()).isEqualTo(PaymentStatus.FAILED);
            Payment b = newIntent(); b.markCancelled(); assertThat(b.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            Payment c = newIntent(); c.markExpired();   assertThat(c.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        }

        @Test
        void pending_toFailed_toCancelled_toExpired_allAllowed() {
            Payment a = newIntent(); a.markPending("PSP-REF-A"); a.markFailed();    assertThat(a.getStatus()).isEqualTo(PaymentStatus.FAILED);
            Payment b = newIntent(); b.markPending("PSP-REF-B"); b.markCancelled(); assertThat(b.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            Payment c = newIntent(); c.markPending("PSP-REF-C"); c.markExpired();   assertThat(c.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
            // providerReference survives every subsequent transition unchanged - these are the
            // PENDING -> terminal transitions; none of them touch it.
            assertThat(a.getProviderReference()).isEqualTo("PSP-REF-A");
            assertThat(b.getProviderReference()).isEqualTo("PSP-REF-B");
            assertThat(c.getProviderReference()).isEqualTo("PSP-REF-C");
        }

        @Test
        void expired_toSucceeded_isLateSuccessRecoveryPath() {
            Payment p = newIntent();
            p.markPending("PSP-REF-LATE");
            p.markExpired();
            p.markSucceeded("TX-late", new BigDecimal("10000.00"));
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(p.isLateSuccessAfterExpiry()).isTrue();
            assertThat(p.getProviderTransactionId()).isEqualTo("TX-late");
            // providerReference survives the full PENDING -> EXPIRED -> SUCCEEDED path unchanged.
            assertThat(p.getProviderReference()).isEqualTo("PSP-REF-LATE");
        }
    }

    @Nested
    @DisplayName("illegal transitions")
    class IllegalTransitions {

        @Test
        void created_directlyToSucceeded_rejected() {
            assertThatThrownBy(() -> newIntent().markSucceeded("TX", BigDecimal.TEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void failed_toSucceeded_rejected() {
            Payment p = newIntent(); p.markFailed();
            assertThatThrownBy(() -> p.markSucceeded("TX", BigDecimal.TEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void cancelled_toSucceeded_rejected() {
            Payment p = newIntent(); p.markCancelled();
            assertThatThrownBy(() -> p.markSucceeded("TX", BigDecimal.TEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void succeeded_toAnything_rejected() {
            Payment p = newIntent(); p.markPending("PSP-REF-1"); p.markSucceeded("TX-1", BigDecimal.TEN);
            assertThatThrownBy(p::markFailed).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(p::markCancelled).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(p::markExpired).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> p.markPending("PSP-REF-2")).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void succeeded_secondMarkSucceeded_rejected() {
            Payment p = newIntent(); p.markPending("PSP-REF-1"); p.markSucceeded("TX-1", BigDecimal.TEN);
            assertThatThrownBy(() -> p.markSucceeded("TX-2", BigDecimal.ONE))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("write-once provider fields")
    class WriteOnce {

        @Test
        void providerTransactionId_isWriteOnce() {
            Payment p = newIntent(); p.markPending("PSP-REF-1");
            p.markSucceeded("TX-1", new BigDecimal("100"));
            // second success attempt (any status) must never replace the bound id
            assertThatThrownBy(() -> p.markSucceeded("TX-2", new BigDecimal("100")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(p.getProviderTransactionId()).isEqualTo("TX-1");
        }

        @Test
        void amountConfirmed_isWriteOnce_andMustBePositive() {
            Payment p = newIntent(); p.markPending("PSP-REF-1");
            assertThatThrownBy(() -> p.markSucceeded("TX-1", BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(p.getAmountConfirmed()).isNull();
            p.markSucceeded("TX-1", new BigDecimal("999"));
            assertThat(p.getAmountConfirmed()).isEqualByComparingTo("999");
        }

        @Test
        void providerReference_nullRejected() {
            Payment p = newIntent();
            assertThatThrownBy(() -> p.markPending(null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CREATED);
            assertThat(p.getProviderReference()).isNull();
        }

        @Test
        void providerReference_blankOrWhitespaceRejected() {
            Payment p = newIntent();
            assertThatThrownBy(() -> p.markPending("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> p.markPending("   ")).isInstanceOf(IllegalArgumentException.class);
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CREATED);
            assertThat(p.getProviderReference()).isNull();
        }

        @Test
        void providerReference_isWriteOnce_secondMarkPendingRejectedAndValueUnchanged() {
            Payment p = newIntent();
            p.markPending("PSP-REF-FIRST");
            // a second markPending call - same value or a different one - is rejected by the
            // existing status guard (no longer CREATED) before any reassignment could occur.
            assertThatThrownBy(() -> p.markPending("PSP-REF-FIRST")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> p.markPending("PSP-REF-SECOND")).isInstanceOf(IllegalStateException.class);
            assertThat(p.getProviderReference()).isEqualTo("PSP-REF-FIRST");
        }
    }

    @Nested
    @DisplayName("effective expiry shortening")
    class ExpiryShortening {

        @Test
        void earlierProviderExpiry_shortensAndTouches() {
            Payment p = newIntent(); // expiresAt = NOW + 30m
            OffsetDateTime before = p.getUpdatedAt();
            OffsetDateTime earlier = NOW.plusMinutes(10);
            p.shortenExpiryTo(earlier);
            assertThat(p.getExpiresAt()).isEqualTo(earlier);
            // Strictly isAfter, not isAfterOrEqualTo: NOW is a fixed constant safely in the past
            // relative to real wall-clock execution time, so touch()'s OffsetDateTime.now() is
            // deterministically later than `before` whenever touch() actually ran, and equal to
            // `before` (never greater) whenever it did not - isAfterOrEqualTo could not tell the
            // two cases apart and therefore did not prove touch() fired. No sleep needed.
            assertThat(p.getUpdatedAt()).isAfter(before);
        }

        @Test
        void equalProviderExpiry_isNoOp_doesNotTouch() {
            Payment p = newIntent();
            OffsetDateTime before = p.getUpdatedAt();
            p.shortenExpiryTo(EXP); // exactly equal to current expiresAt
            assertThat(p.getExpiresAt()).isEqualTo(EXP);
            assertThat(p.getUpdatedAt()).isEqualTo(before);
        }

        @Test
        void laterProviderExpiry_rejected_expiryUnchanged() {
            Payment p = newIntent();
            OffsetDateTime later = EXP.plusMinutes(10);
            assertThatThrownBy(() -> p.shortenExpiryTo(later)).isInstanceOf(IllegalArgumentException.class);
            assertThat(p.getExpiresAt()).isEqualTo(EXP);
        }

        @Test
        void nullProviderExpiry_rejected() {
            Payment p = newIntent();
            assertThatThrownBy(() -> p.shortenExpiryTo(null)).isInstanceOf(NullPointerException.class);
            assertThat(p.getExpiresAt()).isEqualTo(EXP);
        }

        @Test
        void canShortenAcrossPendingLifecycle() {
            Payment p = newIntent();
            p.markPending("PSP-REF-1");
            OffsetDateTime earlier = NOW.plusMinutes(5);
            p.shortenExpiryTo(earlier);
            assertThat(p.getExpiresAt()).isEqualTo(earlier);
        }

        @Test
        void terminalStates_rejectExpiryShortening() {
            OffsetDateTime earlier = NOW.plusMinutes(5);

            Payment succeeded = newIntent(); succeeded.markPending("PSP-REF-S"); succeeded.markSucceeded("TX-S", BigDecimal.TEN);
            assertThatThrownBy(() -> succeeded.shortenExpiryTo(earlier)).isInstanceOf(IllegalStateException.class);

            Payment failed = newIntent(); failed.markFailed();
            assertThatThrownBy(() -> failed.shortenExpiryTo(earlier)).isInstanceOf(IllegalStateException.class);

            Payment cancelled = newIntent(); cancelled.markCancelled();
            assertThatThrownBy(() -> cancelled.shortenExpiryTo(earlier)).isInstanceOf(IllegalStateException.class);

            Payment expired = newIntent(); expired.markExpired();
            assertThatThrownBy(() -> expired.shortenExpiryTo(earlier)).isInstanceOf(IllegalStateException.class);
        }
    }
}
