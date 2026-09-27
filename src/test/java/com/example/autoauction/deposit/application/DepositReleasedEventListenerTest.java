// deposit/application/DepositReleasedEventListenerTest.java
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
 * G2: pure-unit coverage of {@link DepositReleasedEventListener}'s own logic - that it sends
 * exactly the same DEPOSIT_RELEASED notification content that used to live inline in
 * {@code DepositService.releaseWinnerDeposit}, and that a notification failure never propagates
 * out of {@code onDepositReleased}. This class does NOT prove transaction semantics - that is
 * {@link DepositReleasedEventAfterCommitPgIntegrationTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты DepositReleasedEventListener")
class DepositReleasedEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private DepositReleasedEventListener listener;

    private final Long userId = 100L;
    private final Long auctionId = 1L;
    private final BigDecimal amount = new BigDecimal("10000.00");

    @Nested
    @DisplayName("Успешная диспетчеризация")
    class SuccessfulDispatch {

        @Test
        @DisplayName("Отправляется DEPOSIT_RELEASED с точным содержимым (title/body/аргументы)")
        void successfulDispatch_SendsExactNotificationContent() {
            DepositReleasedEvent event = new DepositReleasedEvent(userId, auctionId, amount);

            listener.onDepositReleased(event);

            verify(notificationService, times(1)).send(
                    eq(userId),
                    eq("DEPOSIT_RELEASED"),
                    eq("Депозит освобождён"),
                    eq(String.format("Заморозка %s сом по аукциону #%d снята администратором.",
                            amount.toPlainString(), auctionId))
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
                    .send(eq(userId), eq("DEPOSIT_RELEASED"), org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString());
            DepositReleasedEvent event = new DepositReleasedEvent(userId, auctionId, amount);

            assertThatCode(() -> listener.onDepositReleased(event)).doesNotThrowAnyException();
        }
    }
}
