package com.example.autoauction.auction.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Slice 1B: PaymentDeadlineHistory — actor/previous-value validation")
class PaymentDeadlineHistoryTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-10T10:00:00Z");
    private static final OffsetDateTime DEADLINE = OffsetDateTime.parse("2026-09-15T18:00:00+06:00");

    @Test
    void initial_validCombination_accepted() {
        PaymentDeadlineHistory h = PaymentDeadlineHistory.initial(1L, DEADLINE, NOW);

        assertThat(h.getChangeType()).isEqualTo(PaymentDeadlineChangeType.INITIAL);
        assertThat(h.getAuctionId()).isEqualTo(1L);
        assertThat(h.getNewDeadline()).isEqualTo(DEADLINE);
        assertThat(h.getPreviousDeadline()).isNull();
        assertThat(h.getChangedByAdminId()).isNull();
        assertThat(h.getReason()).isEqualTo(PaymentDeadlineHistory.SYSTEM_INITIAL_REASON);
        assertThat(h.getReason()).isNotBlank();
    }

    @Test
    void adminExtension_validCombination_accepted() {
        PaymentDeadlineHistory h = PaymentDeadlineHistory.adminExtension(
                1L, DEADLINE, DEADLINE.plusDays(2), "покупатель попросил отсрочку", 42L, NOW);

        assertThat(h.getChangeType()).isEqualTo(PaymentDeadlineChangeType.ADMIN_EXTENSION);
        assertThat(h.getPreviousDeadline()).isEqualTo(DEADLINE);
        assertThat(h.getNewDeadline()).isEqualTo(DEADLINE.plusDays(2));
        assertThat(h.getChangedByAdminId()).isEqualTo(42L);
        assertThat(h.getReason()).isEqualTo("покупатель попросил отсрочку");
    }

    @Test
    void initial_withAdminActor_rejected() {
        assertThatThrownBy(() -> new PaymentDeadlineHistory(1L, PaymentDeadlineChangeType.INITIAL,
                null, DEADLINE, PaymentDeadlineHistory.SYSTEM_INITIAL_REASON, 42L, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initial_withPreviousDeadline_rejected() {
        assertThatThrownBy(() -> new PaymentDeadlineHistory(1L, PaymentDeadlineChangeType.INITIAL,
                DEADLINE, DEADLINE.plusDays(1), PaymentDeadlineHistory.SYSTEM_INITIAL_REASON, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adminExtension_withoutActor_rejected() {
        assertThatThrownBy(() -> new PaymentDeadlineHistory(1L, PaymentDeadlineChangeType.ADMIN_EXTENSION,
                DEADLINE, DEADLINE.plusDays(1), "reason", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adminExtension_withoutPreviousDeadline_rejected() {
        assertThatThrownBy(() -> new PaymentDeadlineHistory(1L, PaymentDeadlineChangeType.ADMIN_EXTENSION,
                null, DEADLINE, "reason", 42L, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankReason_rejected() {
        assertThatThrownBy(() -> new PaymentDeadlineHistory(1L, PaymentDeadlineChangeType.ADMIN_EXTENSION,
                DEADLINE, DEADLINE.plusDays(1), "   ", 42L, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullReason_rejected() {
        assertThatThrownBy(() -> new PaymentDeadlineHistory(1L, PaymentDeadlineChangeType.ADMIN_EXTENSION,
                DEADLINE, DEADLINE.plusDays(1), null, 42L, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullNewDeadline_rejected() {
        assertThatThrownBy(() -> new PaymentDeadlineHistory(1L, PaymentDeadlineChangeType.ADMIN_EXTENSION,
                DEADLINE, null, "reason", 42L, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullAuctionId_rejected() {
        assertThatThrownBy(() -> PaymentDeadlineHistory.initial(null, DEADLINE, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reconstructionConstructor_restoresId() {
        PaymentDeadlineHistory h = new PaymentDeadlineHistory(99L, 1L, PaymentDeadlineChangeType.ADMIN_EXTENSION,
                DEADLINE, DEADLINE.plusDays(1), "reason", 42L, NOW);
        assertThat(h.getId()).isEqualTo(99L);
    }
}
