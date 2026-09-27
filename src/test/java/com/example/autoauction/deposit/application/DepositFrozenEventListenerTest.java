// deposit/application/DepositFrozenEventListenerTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.notification.application.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * G1: pure-unit coverage of {@link DepositFrozenEventListener}'s own logic - that it sends
 * exactly the same DEPOSIT_FROZEN notification content that used to live inline in
 * {@code DepositService.freezeForAuction}, and that a notification failure never propagates out
 * of {@code onDepositFrozen}. This class does NOT prove transaction semantics - that is
 * {@link DepositFrozenEventAfterCommitPgIntegrationTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты DepositFrozenEventListener")
class DepositFrozenEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private DepositFrozenEventListener listener;

    private final Long userId = 100L;
    private final Long auctionId = 1L;
    private final BigDecimal requiredAmount = new BigDecimal("10000.00");

    @Nested
    @DisplayName("Успешная диспетчеризация")
    class SuccessfulDispatch {

        @Test
        @DisplayName("Отправляется DEPOSIT_FROZEN с точным содержимым (title/body/аргументы)")
        void successfulDispatch_SendsExactNotificationContent() {
            DepositFrozenEvent event = new DepositFrozenEvent(userId, auctionId, requiredAmount);

            listener.onDepositFrozen(event);

            verify(notificationService, times(1)).send(
                    eq(userId),
                    eq("DEPOSIT_FROZEN"),
                    eq("Депозит заморожен"),
                    eq(String.format("Для участия в аукционе #%d заморожено %s сом.",
                            auctionId, requiredAmount.toPlainString()))
            );
        }
    }

    @Nested
    @DisplayName("Изоляция ошибок (AFTER_COMMIT - ничего не должно \"всплыть\" наружу)")
    class ExceptionIsolation {

        @Test
        @DisplayName("Падение notificationService.send не всплывает наружу из listener'а")
        void notificationFailure_DoesNotPropagate() {
            doThrow(new RuntimeException("boom")).when(notificationService)
                    .send(eq(userId), eq("DEPOSIT_FROZEN"), org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString());
            DepositFrozenEvent event = new DepositFrozenEvent(userId, auctionId, requiredAmount);

            assertThatCode(() -> listener.onDepositFrozen(event)).doesNotThrowAnyException();
        }
    }
}
