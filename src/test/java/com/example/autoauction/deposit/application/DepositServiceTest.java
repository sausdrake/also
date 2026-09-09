// deposit/application/DepositServiceTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.common.application.FailedOperationService;
import com.example.autoauction.deposit.domain.*;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.deposit.web.dto.DepositTransactionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты DepositService - сервиса управления депозитами")
class DepositServiceTest {

    @Mock
    private DepositRepository depositRepository;

    @Mock
    private FailedOperationService failedOperationService;

    @InjectMocks
    private DepositService depositService;

    private Deposit deposit;
    private Deposit depositWithBalance;
    private Deposit depositWithBlocked;
    private final Long userId = 1L;
    private final Long anotherUserId = 2L;
    private final Long auctionId = 100L;
    private final Long depositId = 10L;
    private final BigDecimal requiredDeposit = new BigDecimal("10000.00");
    private final BigDecimal depositAmount = new BigDecimal("50000");
    private final BigDecimal withdrawAmount = new BigDecimal("30000");
    private final BigDecimal zero = BigDecimal.ZERO;
    private final BigDecimal negativeAmount = new BigDecimal("-1000");

    @BeforeEach
    void setUp() {
        deposit = new Deposit(userId, zero);
        deposit.setId(depositId);
        deposit.setCreatedAt(OffsetDateTime.now());
        deposit.setUpdatedAt(OffsetDateTime.now());

        depositWithBalance = new Deposit(userId, depositAmount);
        depositWithBalance.setId(depositId);

        depositWithBlocked = new Deposit(userId, depositAmount);
        depositWithBlocked.setId(depositId);
        // ИСПРАВЛЕНО: вызываем freezeForAuction с одним параметром (BigDecimal)
        depositWithBlocked.freezeForAuction(requiredDeposit);
    }

    // ==================== SELF-SERVICE DEPOSIT DISABLED TESTS (D-1) ====================

    @Nested
    @DisplayName("Самостоятельное пополнение депозита отключено")
    class SelfServiceDepositDisabledTests {

        @Test
        @DisplayName("deposit() всегда выбрасывает исключение и не обращается к репозиторию")
        void deposit_AlwaysThrows_NoRepositoryMutation() {
            assertThrows(IllegalStateException.class,
                    () -> depositService.deposit(userId, new BigDecimal("100")));

            verifyNoInteractions(depositRepository);
        }
    }

    // ==================== WITHDRAW IDEMPOTENCY TESTS (R-1.3) ====================

    @Nested
    @DisplayName("Вывод средств — идемпотентность")
    class WithdrawTests {

        @Test
        @DisplayName("Первый вывод с ключом идемпотентности — успешен")
        void withdraw_FirstAttempt_Succeeds() {
            when(depositRepository.findTransactionByIdempotencyKey("wkey-1")).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            DepositResponse response = depositService.withdraw(userId, withdrawAmount, "wkey-1");

            assertEquals(0, response.balance().compareTo(depositAmount.subtract(withdrawAmount)));
            verify(depositRepository, times(1)).save(any(Deposit.class));
            verify(depositRepository, times(1)).saveTransaction(any(DepositTransaction.class));
        }

        @Test
        @DisplayName("Повтор с тем же ключом — не списывает повторно, возвращает текущее состояние")
        void withdraw_SameKeyReplay_DoesNotWithdrawTwice() {
            DepositTransaction previous = new DepositTransaction(
                    depositId, userId, DepositTransaction.TransactionType.WITHDRAWAL,
                    withdrawAmount, depositAmount, depositAmount.subtract(withdrawAmount),
                    "WITHDRAWAL", "Вывод средств с депозита"
            );
            previous.setIdempotencyKey("wkey-2");
            when(depositRepository.findTransactionByIdempotencyKey("wkey-2")).thenReturn(Optional.of(previous));
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            DepositResponse response = depositService.withdraw(userId, withdrawAmount, "wkey-2");

            assertEquals(0, response.balance().compareTo(depositWithBalance.getBalance()));
            verify(depositRepository, never()).save(any(Deposit.class));
            verify(depositRepository, never()).saveTransaction(any(DepositTransaction.class));
        }

        @Test
        @DisplayName("Конкурентные запросы с одним и тем же ключом — второй проигрывает гонку на уровне БД, откат без списания")
        void withdraw_ConcurrentSameKey_SecondLosesRaceAtDbLevel() throws Exception {
            when(depositRepository.findTransactionByIdempotencyKey("wkey-race")).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            // Первый вызов сохраняет транзакцию успешно; второй (конкурентный, тот же ключ)
            // получает нарушение уникального индекса — как это реально произошло бы в БД.
            when(depositRepository.saveTransaction(any()))
                    .thenAnswer(i -> i.getArgument(0))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "duplicate key value violates unique constraint \"uk_deposit_transactions_idempotency_key\""));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        depositService.withdraw(userId, withdrawAmount, "wkey-race");
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(1, successCount.get(), "Ровно одна из двух конкурентных попыток должна успешно списать средства");
            assertEquals(1, failureCount.get(), "Вторая должна проиграть гонку на уровне БД-constraint, а не списать повторно");
            // save(deposit) вызывается ровно один раз - для победившей попытки. Проигравшая
            // бросает исключение на saveTransaction() ДО вызова save(deposit).
            verify(depositRepository, times(1)).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Разные ключи — независимые операции, обе применяются")
        void withdraw_DifferentKeys_BothApplyIndependently() {
            when(depositRepository.findTransactionByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.withdraw(userId, new BigDecimal("1000"), "wkey-A");
            depositService.withdraw(userId, new BigDecimal("2000"), "wkey-B");

            verify(depositRepository, times(2)).save(any(Deposit.class));
            verify(depositRepository, times(2)).saveTransaction(any(DepositTransaction.class));
        }

        @Test
        @DisplayName("Недостаточно средств — IllegalStateException, транзакция не создаётся")
        void withdraw_InsufficientBalance_ThrowsAndCreatesNoTransaction() {
            when(depositRepository.findTransactionByIdempotencyKey("wkey-poor")).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit)); // нулевой баланс

            assertThrows(IllegalStateException.class,
                    () -> depositService.withdraw(userId, withdrawAmount, "wkey-poor"));

            verify(depositRepository, never()).saveTransaction(any());
            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Отсутствующий ключ идемпотентности — IllegalArgumentException")
        void withdraw_MissingKey_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.withdraw(userId, withdrawAmount, null));

            verifyNoInteractions(depositRepository);
        }

        @Test
        @DisplayName("Пустой ключ идемпотентности — IllegalArgumentException")
        void withdraw_BlankKey_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.withdraw(userId, withdrawAmount, "   "));

            verifyNoInteractions(depositRepository);
        }

        @Test
        @DisplayName("Провенанс транзакции: тип WITHDRAWAL, корректные balanceBefore/After, сохранён ключ")
        void withdraw_TransactionProvenance_IsCorrect() {
            when(depositRepository.findTransactionByIdempotencyKey("wkey-prov")).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.withdraw(userId, withdrawAmount, "wkey-prov");

            ArgumentCaptor<DepositTransaction> cap = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository).saveTransaction(cap.capture());
            DepositTransaction tx = cap.getValue();
            assertEquals(DepositTransaction.TransactionType.WITHDRAWAL, tx.getType());
            assertEquals(0, tx.getBalanceBefore().compareTo(depositAmount));
            assertEquals(0, tx.getBalanceAfter().compareTo(depositAmount.subtract(withdrawAmount)));
            assertEquals("wkey-prov", tx.getIdempotencyKey());
        }

        @Test
        @DisplayName("Follow-up #2: тот же ключ с ДРУГОЙ суммой — IllegalStateException, повторного списания не происходит")
        void withdraw_SameKeyDifferentAmount_Throws() {
            DepositTransaction previous = new DepositTransaction(
                    depositId, userId, DepositTransaction.TransactionType.WITHDRAWAL,
                    new BigDecimal("1000"), depositAmount, depositAmount.subtract(new BigDecimal("1000")),
                    "WITHDRAWAL", "Вывод средств с депозита"
            );
            previous.setIdempotencyKey("wfp-key-1");
            when(depositRepository.findTransactionByIdempotencyKey("wfp-key-1")).thenReturn(Optional.of(previous));

            assertThrows(IllegalStateException.class,
                    () -> depositService.withdraw(userId, new BigDecimal("2000"), "wfp-key-1"));

            verify(depositRepository, never()).save(any(Deposit.class));
            verify(depositRepository, never()).saveTransaction(any(DepositTransaction.class));
        }

        @Test
        @DisplayName("Follow-up #2: тот же ключ для ДРУГОГО пользователя — IllegalStateException")
        void withdraw_SameKeyDifferentUser_Throws() {
            DepositTransaction previous = new DepositTransaction(
                    depositId, anotherUserId, DepositTransaction.TransactionType.WITHDRAWAL,
                    new BigDecimal("1000"), depositAmount, depositAmount.subtract(new BigDecimal("1000")),
                    "WITHDRAWAL", "Вывод средств с депозита"
            );
            previous.setIdempotencyKey("wfp-key-2");
            when(depositRepository.findTransactionByIdempotencyKey("wfp-key-2")).thenReturn(Optional.of(previous));

            assertThrows(IllegalStateException.class,
                    () -> depositService.withdraw(userId, new BigDecimal("1000"), "wfp-key-2"));

            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Follow-up #2: тот же ключ, тот же пользователь и та же сумма — идемпотентный повтор")
        void withdraw_SameKeySameFingerprint_IsIdempotentReplay() {
            DepositTransaction previous = new DepositTransaction(
                    depositId, userId, DepositTransaction.TransactionType.WITHDRAWAL,
                    withdrawAmount, depositAmount, depositAmount.subtract(withdrawAmount),
                    "WITHDRAWAL", "Вывод средств с депозита"
            );
            previous.setIdempotencyKey("wfp-key-3");
            when(depositRepository.findTransactionByIdempotencyKey("wfp-key-3")).thenReturn(Optional.of(previous));
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            DepositResponse response = depositService.withdraw(userId, withdrawAmount, "wfp-key-3");

            assertEquals(0, response.balance().compareTo(depositWithBalance.getBalance()));
            verify(depositRepository, never()).save(any(Deposit.class));
        }
    }

    // ==================== FREEZE FOR AUCTION TESTS ====================

    @Nested
    @DisplayName("Заморозка депозита для аукциона")
    class FreezeForAuctionTests {

        @Test
        @DisplayName("Заморозка при достаточном балансе")
        void freezeForAuction_Success() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.isFrozenForAuction(depositId, auctionId)).thenReturn(false);
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            depositService.freezeForAuction(userId, auctionId, requiredDeposit);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());

            Deposit savedDeposit = depositCaptor.getValue();
            assertEquals(requiredDeposit, savedDeposit.getBlockedAmount());
            assertEquals(depositAmount.subtract(requiredDeposit), savedDeposit.getAvailableBalance());

            // Проверяем вызов freezeForAuction в репозитории
            verify(depositRepository, times(1)).freezeForAuction(
                    eq(depositId), eq(auctionId), eq(requiredDeposit), any()
            );

            // Проверяем транзакцию
            ArgumentCaptor<DepositTransaction> transactionCaptor =
                    ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(transactionCaptor.capture());

            DepositTransaction transaction = transactionCaptor.getValue();
            assertEquals(DepositTransaction.TransactionType.BLOCK, transaction.getType());
            assertEquals(requiredDeposit, transaction.getAmount());
            assertEquals(auctionId, transaction.getAuctionId());
        }

        @Test
        @DisplayName("Многократная заморозка для разных аукционов")
        void freezeForAuction_MultipleFreezes_Success() {
            // given - депозит с большим балансом
            BigDecimal bigBalance = new BigDecimal("100000");
            Deposit richDeposit = new Deposit(userId, bigBalance);
            richDeposit.setId(depositId);

            Long auctionId2 = 200L;
            BigDecimal requiredDeposit2 = new BigDecimal("20000.00");

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(richDeposit));
            when(depositRepository.isFrozenForAuction(eq(depositId), anyLong()))
                    .thenReturn(false);
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when - первая заморозка (другой фиксированный депозит для этого аукциона)
            depositService.freezeForAuction(userId, auctionId, requiredDeposit);

            // when - вторая заморозка (независимая фиксированная сумма для другого аукциона)
            depositService.freezeForAuction(userId, auctionId2, requiredDeposit2);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(2)).save(depositCaptor.capture());

            List<Deposit> savedDeposits = depositCaptor.getAllValues();
            Deposit lastSaved = savedDeposits.get(1);

            BigDecimal expectedBlocked = requiredDeposit.add(requiredDeposit2);
            assertEquals(expectedBlocked, lastSaved.getBlockedAmount());
        }

        @Test
        @DisplayName("Заморозка при недостаточном балансе - IllegalStateException")
        void freezeForAuction_InsufficientFunds_ThrowsException() {
            // given - депозит с нулевым балансом
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> depositService.freezeForAuction(userId, auctionId, requiredDeposit));
            assertTrue(exception.getMessage().contains("Недостаточно средств"));

            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Заморозка с null требуемой суммой - IllegalArgumentException")
        void freezeForAuction_NullRequiredAmount_ThrowsException() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.freezeForAuction(userId, auctionId, null));
        }
    }

    // ==================== UNFREEZE FOR AUCTION TESTS ====================

    @Nested
    @DisplayName("Разморозка депозита")
    class UnfreezeForAuctionTests {

        @Test
        @DisplayName("Успешная разморозка")
        void unfreezeForAuction_Success() {
            // given
            FrozenDeposit frozenDeposit = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId))
                    .thenReturn(List.of(frozenDeposit));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            depositService.unfreezeForAuction(userId, auctionId);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());

            Deposit savedDeposit = depositCaptor.getValue();

            // ИСПРАВЛЕНО: используем BigDecimal.ZERO вместо 0
            assertEquals(0, savedDeposit.getBlockedAmount().compareTo(BigDecimal.ZERO));
            assertTrue(savedDeposit.getAvailableBalance().compareTo(depositAmount) == 0);}

        @Test
        @DisplayName("Депозит не найден — ошибка сохраняется через FailedOperationService")
        void unfreezeForAuction_DepositNotFound_RecordsFailedOperation() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            // Не должно выбрасывать исключение наружу
            depositService.unfreezeForAuction(userId, auctionId);

            verify(failedOperationService, times(1)).saveFailedOperation(
                    eq("UNFREEZE_AUCTION"), eq(userId), eq(auctionId), any(IllegalArgumentException.class));
        }

        @Test
        @DisplayName("Непредвиденная ошибка при разморозке — сохраняется через FailedOperationService")
        void unfreezeForAuction_UnexpectedError_RecordsFailedOperation() {
            FrozenDeposit frozenDeposit = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId)).thenReturn(List.of(frozenDeposit));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenThrow(new RuntimeException("DB down"));

            depositService.unfreezeForAuction(userId, auctionId);

            verify(failedOperationService, times(1)).saveFailedOperation(
                    eq("UNFREEZE_AUCTION"), eq(userId), eq(auctionId), any(RuntimeException.class));
        }
    }
    // ==================== MARK AS WINNER TESTS ====================

    @Nested
    @DisplayName("Отметка победителя аукциона")
    class MarkAsWinnerTests {

        @Test
        @DisplayName("Успешная отметка победителя - депозит остаётся замороженным")
        void markAsWinner_Success() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            doNothing().when(depositRepository).markFrozenAsWinner(depositId, auctionId);

            // when
            depositService.markAsWinner(userId, auctionId);

            // then
            verify(depositRepository, times(1)).markFrozenAsWinner(depositId, auctionId);
        }

        @Test
        @DisplayName("Отметка победителя для несуществующего депозита - исключение")
        void markAsWinner_DepositNotFound_ThrowsException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.markAsWinner(userId, auctionId));
        }
    }

    // ==================== CAN PARTICIPATE TESTS ====================

    @Nested
    @DisplayName("Проверка возможности участия в аукционе")
    class CanParticipateTests {

        @Test
        @DisplayName("Достаточно средств для фиксированной суммы депозита лота - true")
        void canParticipateInAuction_WithEnoughFunds_ReturnsTrue() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            boolean result = depositService.canParticipateInAuction(userId, requiredDeposit);

            assertTrue(result);
        }

        @Test
        @DisplayName("Недостаточно средств для фиксированной суммы депозита лота - false")
        void canParticipateInAuction_InsufficientFunds_ReturnsFalse() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));

            boolean result = depositService.canParticipateInAuction(userId, requiredDeposit);

            assertFalse(result);
        }

        @Test
        @DisplayName("Депозит не существует - false")
        void canParticipateInAuction_NoDeposit_ReturnsFalse() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            boolean result = depositService.canParticipateInAuction(userId, requiredDeposit);

            assertFalse(result);
        }

        @Test
        @DisplayName("D-2: сумма депозита используется напрямую, без расчёта процента от стартовой цены аукциона")
        void canParticipateInAuction_DoesNotDeriveFromStartingPrice() {
            // Депозит с балансом ровно между 1% от startingPrice (10000) и самим startingPrice
            // (1000000, "по-старому" неверная база расчёта). Если бы где-то в проде осталась
            // процентная формула, она сравнила бы availableBalance с 1%*startingPrice=10000
            // и вернула true; текущая (верная) реализация сравнивает баланс с переданной суммой
            // напрямую.
            Deposit midBalanceDeposit = new Deposit(userId, new BigDecimal("500000"));
            midBalanceDeposit.setId(depositId);
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(midBalanceDeposit));

            // Фиксированная сумма депозита лота (500001) немного больше баланса (500000).
            boolean result = depositService.canParticipateInAuction(userId, new BigDecimal("500001"));

            assertFalse(result, "Ожидался прямой сравнение с переданной суммой, а не производный процент от startingPrice");
        }
    }

    // ==================== GET DEPOSIT TESTS ====================

    @Nested
    @DisplayName("Получение депозита")
    class GetDepositTests {

        @Test
        @DisplayName("Успешное получение депозита по userId")
        void getDepositByUserId_Success() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            DepositResponse response = depositService.getDepositByUserId(userId);

            assertNotNull(response);
            assertEquals(depositId, response.id());
            assertEquals(depositAmount, response.balance());
        }

        @Test
        @DisplayName("Депозит не найден - исключение")
        void getDepositByUserId_NotFound_ThrowsException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.getDepositByUserId(userId));
        }
    }

    // ==================== GET TRANSACTIONS TESTS ====================

    @Nested
    @DisplayName("Получение транзакций")
    class GetTransactionsTests {

        @Test
        @DisplayName("Успешное получение транзакций")
        void getTransactionsByUserId_Success() {
            // given
            DepositTransaction transaction = new DepositTransaction(
                    depositId, userId, DepositTransaction.TransactionType.DEPOSIT,
                    depositAmount, zero, depositAmount, "TEST", "Test transaction"
            );
            when(depositRepository.findTransactionsByUserId(userId))
                    .thenReturn(List.of(transaction));

            // when
            List<DepositTransactionResponse> responses =
                    depositService.getTransactionsByUserId(userId);

            // then
            assertNotNull(responses);
            assertEquals(1, responses.size());
            assertEquals(DepositTransaction.TransactionType.DEPOSIT, responses.get(0).type());
            assertEquals(depositAmount, responses.get(0).amount());
        }
    }

    // ==================== ADMIN ADJUST TESTS ====================

    @Nested
    @DisplayName("Административная корректировка депозита")
    class AdminAdjustTests {

        @Test
        @DisplayName("Положительная корректировка — пополняет баланс")
        void adminAdjust_Positive_IncreasesBalance() {
            when(depositRepository.findTransactionByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            BigDecimal topUp = new BigDecimal("5000");
            depositService.adminAdjust(userId, topUp, "тест пополнение", "key-1");

            ArgumentCaptor<Deposit> cap = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository).save(cap.capture());
            assertEquals(0, cap.getValue().getBalance().compareTo(depositAmount.add(topUp)));
        }

        @Test
        @DisplayName("Отрицательная корректировка — списывает с баланса")
        void adminAdjust_Negative_DecreasesBalance() {
            when(depositRepository.findTransactionByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            BigDecimal deduction = new BigDecimal("-10000");
            depositService.adminAdjust(userId, deduction, "возврат за ошибку", "key-2");

            ArgumentCaptor<Deposit> cap = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository).save(cap.capture());
            assertEquals(0, cap.getValue().getBalance()
                    .compareTo(depositAmount.add(deduction)));
        }

        @Test
        @DisplayName("Отрицательная корректировка больше доступного баланса — исключение")
        void adminAdjust_NegativeExceedsBalance_Throws() {
            when(depositRepository.findTransactionByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            BigDecimal tooMuch = new BigDecimal("-999999");
            assertThrows(IllegalStateException.class,
                    () -> depositService.adminAdjust(userId, tooMuch, "удаление", "key-3"));
        }

        @Test
        @DisplayName("Нулевая корректировка — IllegalArgumentException")
        void adminAdjust_ZeroAmount_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.adminAdjust(userId, BigDecimal.ZERO, "ноль", null));
        }

        @Test
        @DisplayName("Null сумма — IllegalArgumentException")
        void adminAdjust_NullAmount_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.adminAdjust(userId, null, "комментарий", null));
        }

        @Test
        @DisplayName("Пустой комментарий — IllegalArgumentException")
        void adminAdjust_BlankComment_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.adminAdjust(userId, new BigDecimal("1000"), "  ", null));
        }

        @Test
        @DisplayName("Null комментарий — IllegalArgumentException")
        void adminAdjust_NullComment_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.adminAdjust(userId, new BigDecimal("1000"), null, null));
        }

        @Test
        @DisplayName("Превышение MAX_ADJUST_AMOUNT — IllegalArgumentException")
        void adminAdjust_ExceedsMaxAmount_Throws() {
            BigDecimal over = DepositService.MAX_ADJUST_AMOUNT.add(BigDecimal.ONE);
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.adminAdjust(userId, over, "слишком много", null));
        }

        @Test
        @DisplayName("Транзакция типа ADMIN_ADJUSTMENT записывается")
        void adminAdjust_SavesAdjustmentTransaction() {
            when(depositRepository.findTransactionByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.adminAdjust(userId, new BigDecimal("1000"), "тест", "key-4");

            ArgumentCaptor<DepositTransaction> txCap = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository).saveTransaction(txCap.capture());
            assertEquals(DepositTransaction.TransactionType.ADMIN_ADJUSTMENT, txCap.getValue().getType());
        }

        @Test
        @DisplayName("Повтор с тем же ключом идемпотентности — не применяет повторное зачисление")
        void adminAdjust_IdempotentReplay_DoesNotDoubleCredit() {
            String key = "bank-ref-123";
            DepositTransaction previous = new DepositTransaction(
                    depositId, userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                    new BigDecimal("5000"), depositAmount, depositAmount.add(new BigDecimal("5000")),
                    "ADMIN_ADJUSTMENT", "первое зачисление"
            );
            previous.setIdempotencyKey(key);

            when(depositRepository.findTransactionByIdempotencyKey(key)).thenReturn(Optional.of(previous));
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            DepositResponse response = depositService.adminAdjust(userId, new BigDecimal("5000"), "тест", key);

            assertEquals(0, response.balance().compareTo(depositWithBalance.getBalance()));
            verify(depositRepository, never()).save(any(Deposit.class));
            verify(depositRepository, never()).saveTransaction(any(DepositTransaction.class));
        }

        @Test
        @DisplayName("Новый ключ идемпотентности — корректировка применяется и ключ сохраняется")
        void adminAdjust_NewIdempotencyKey_AppliesAndStoresKey() {
            String key = "bank-ref-456";
            when(depositRepository.findTransactionByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.adminAdjust(userId, new BigDecimal("2000"), "тест", key);

            ArgumentCaptor<DepositTransaction> txCap = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository).saveTransaction(txCap.capture());
            assertEquals(key, txCap.getValue().getIdempotencyKey());
        }

        @Test
        @DisplayName("R-1.4: пустой ключ идемпотентности — IllegalArgumentException, мутация не выполняется")
        void adminAdjust_BlankIdempotencyKey_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.adminAdjust(userId, new BigDecimal("2000"), "тест", "   "));

            verifyNoInteractions(depositRepository);
        }

        @Test
        @DisplayName("R-1.4: null ключ идемпотентности — IllegalArgumentException, мутация не выполняется")
        void adminAdjust_NullIdempotencyKey_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.adminAdjust(userId, new BigDecimal("2000"), "тест", null));

            verifyNoInteractions(depositRepository);
        }

        @Test
        @DisplayName("R-1.4: конкурентный дубликат ключа — DataIntegrityViolationException на вставке транзакции, " +
                "баланс НЕ сохраняется (нет частичной мутации)")
        void adminAdjust_ConcurrentDuplicateKey_RollsBackWithoutSavingBalance() {
            String key = "race-key";
            when(depositRepository.findTransactionByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            // Симулируем проигрыш гонки на уровне БД: партиционный уникальный индекс
            // uk_deposit_transactions_idempotency_key отклоняет вставку.
            when(depositRepository.saveTransaction(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "duplicate key value violates unique constraint \"uk_deposit_transactions_idempotency_key\""));

            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> depositService.adminAdjust(userId, new BigDecimal("1000"), "тест", key));

            // saveTransaction() выполняется ДО save(deposit) в adminAdjust — если первое
            // бросает исключение, второе никогда не вызывается: баланс не изменяется.
            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("R-1.4: разные ключи для одного и того же пользователя — обе корректировки применяются независимо")
        void adminAdjust_DifferentKeys_BothApplyIndependently() {
            when(depositRepository.findTransactionByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.adminAdjust(userId, new BigDecimal("1000"), "первая", "key-A");
            depositService.adminAdjust(userId, new BigDecimal("2000"), "вторая", "key-B");

            verify(depositRepository, times(2)).save(any(Deposit.class));
            verify(depositRepository, times(2)).saveTransaction(any(DepositTransaction.class));
        }

        @Test
        @DisplayName("Follow-up #2: тот же ключ с ДРУГОЙ суммой — IllegalStateException, повторная мутация не применяется")
        void adminAdjust_SameKeyDifferentAmount_Throws() {
            DepositTransaction previous = new DepositTransaction(
                    depositId, userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                    new BigDecimal("5000"), depositAmount, depositAmount.add(new BigDecimal("5000")),
                    "ADMIN_ADJUSTMENT", "первое зачисление"
            );
            previous.setIdempotencyKey("fp-key-1");
            when(depositRepository.findTransactionByIdempotencyKey("fp-key-1")).thenReturn(Optional.of(previous));

            assertThrows(IllegalStateException.class,
                    () -> depositService.adminAdjust(userId, new BigDecimal("9999"), "другая сумма", "fp-key-1"));

            verify(depositRepository, never()).save(any(Deposit.class));
            verify(depositRepository, never()).saveTransaction(any(DepositTransaction.class));
        }

        @Test
        @DisplayName("Follow-up #2: тот же ключ для ДРУГОГО пользователя — IllegalStateException")
        void adminAdjust_SameKeyDifferentUser_Throws() {
            DepositTransaction previous = new DepositTransaction(
                    depositId, anotherUserId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                    new BigDecimal("5000"), depositAmount, depositAmount.add(new BigDecimal("5000")),
                    "ADMIN_ADJUSTMENT", "первое зачисление"
            );
            previous.setIdempotencyKey("fp-key-2");
            when(depositRepository.findTransactionByIdempotencyKey("fp-key-2")).thenReturn(Optional.of(previous));

            assertThrows(IllegalStateException.class,
                    () -> depositService.adminAdjust(userId, new BigDecimal("5000"), "тот же ключ, другой юзер", "fp-key-2"));

            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Follow-up #2: тот же ключ, тот же пользователь и та же сумма — корректный идемпотентный повтор")
        void adminAdjust_SameKeySameFingerprint_IsIdempotentReplay() {
            DepositTransaction previous = new DepositTransaction(
                    depositId, userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                    new BigDecimal("5000"), depositAmount, depositAmount.add(new BigDecimal("5000")),
                    "ADMIN_ADJUSTMENT", "первое зачисление"
            );
            previous.setIdempotencyKey("fp-key-3");
            when(depositRepository.findTransactionByIdempotencyKey("fp-key-3")).thenReturn(Optional.of(previous));
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            DepositResponse response = depositService.adminAdjust(userId, new BigDecimal("5000"), "тот же запрос повторно", "fp-key-3");

            assertEquals(0, response.balance().compareTo(depositWithBalance.getBalance()));
            verify(depositRepository, never()).save(any(Deposit.class));
        }
    }

    // ==================== CONCURRENCY TESTS ====================

    @Nested
    @DisplayName("Тесты конкурентного доступа")
    class ConcurrencyTests {

        @Test
        @DisplayName("Параллельное пополнение (адм. корректировка) и вывод - данные должны быть консистентны")
        void concurrentDepositAndWithdraw_ShouldBeConsistent() throws Exception {
            // given
            // Self-service deposit() is disabled (D-1); admin adjust is the remaining
            // legitimate crediting path, so it stands in for the "deposit" side of this
            // concurrency test.
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(20);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            Deposit sharedDeposit = new Deposit(userId, new BigDecimal("10000"));
            sharedDeposit.setId(depositId);

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(sharedDeposit));
            when(depositRepository.findTransactionByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            for (int i = 0; i < 10; i++) {
                String key = "concurrency-key-" + i;
                executor.submit(() -> {
                    try {
                        depositService.adminAdjust(userId, new BigDecimal("100"), "concurrency test", key);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });

                String withdrawKey = "concurrency-withdraw-key-" + i;
                executor.submit(() -> {
                    try {
                        depositService.withdraw(userId, new BigDecimal("50"), withdrawKey);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(sharedDeposit.getBalance().compareTo(BigDecimal.ZERO) >= 0);
        }
    }

    // ==================== PHASE 3B: chargeForDiagnosticRequest ====================

    @Nested
    @DisplayName("chargeForDiagnosticRequest() — списание из frozen commitment (Outcome B)")
    class ChargeForDiagnosticRequestTests {

        private final Long requestId = 500L;

        private Deposit depositFor(BigDecimal balance, BigDecimal blocked) {
            Deposit d = new Deposit(userId, balance);
            d.setId(depositId);
            if (blocked.compareTo(BigDecimal.ZERO) > 0) {
                d.freezeForAuction(blocked);
            }
            return d;
        }

        @Test
        @DisplayName("Частичный charge: одно save(), CHARGE и UNBLOCK с корректными суммами")
        void chargeForDiagnosticRequest_PartialCharge_OneSaveCorrectTransactions() {
            Deposit deposit = depositFor(new BigDecimal("20000"), new BigDecimal("10000"));
            when(depositRepository.findById(depositId)).thenReturn(Optional.of(deposit));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.chargeForDiagnosticRequest(depositId, requestId, new BigDecimal("10000"), new BigDecimal("4000"));

            verify(depositRepository, times(1)).save(any(Deposit.class));

            ArgumentCaptor<DepositTransaction> captor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(2)).saveTransaction(captor.capture());
            List<DepositTransaction> saved = captor.getAllValues();

            DepositTransaction charge = saved.stream()
                    .filter(t -> t.getType() == DepositTransaction.TransactionType.CHARGE).findFirst().orElseThrow();
            DepositTransaction unblock = saved.stream()
                    .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK).findFirst().orElseThrow();

            assertEquals(0, charge.getAmount().compareTo(new BigDecimal("4000")));
            assertEquals(0, unblock.getAmount().compareTo(new BigDecimal("6000")));

            assertEquals(0, deposit.getBalance().compareTo(new BigDecimal("16000")));
            assertEquals(0, deposit.getBlockedAmount().compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("balanceBefore/After conventions: CHARGE использует raw balance, UNBLOCK — availableBalance")
        void chargeForDiagnosticRequest_BalanceBeforeAfterConventions() {
            Deposit deposit = depositFor(new BigDecimal("20000"), new BigDecimal("10000"));
            when(depositRepository.findById(depositId)).thenReturn(Optional.of(deposit));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.chargeForDiagnosticRequest(depositId, requestId, new BigDecimal("10000"), new BigDecimal("4000"));

            ArgumentCaptor<DepositTransaction> captor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(2)).saveTransaction(captor.capture());
            List<DepositTransaction> saved = captor.getAllValues();

            DepositTransaction charge = saved.stream()
                    .filter(t -> t.getType() == DepositTransaction.TransactionType.CHARGE).findFirst().orElseThrow();
            DepositTransaction unblock = saved.stream()
                    .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK).findFirst().orElseThrow();

            // CHARGE: raw balance convention (matches WITHDRAWAL/DEPOSIT/ADMIN_ADJUSTMENT) - 20000 -> 16000
            assertEquals(0, charge.getBalanceBefore().compareTo(new BigDecimal("20000")));
            assertEquals(0, charge.getBalanceAfter().compareTo(new BigDecimal("16000")));

            // UNBLOCK: availableBalance convention (matches unfreezeForAuction) - 10000 -> 16000
            assertEquals(0, unblock.getBalanceBefore().compareTo(new BigDecimal("10000")));
            assertEquals(0, unblock.getBalanceAfter().compareTo(new BigDecimal("16000")));
        }

        @Test
        @DisplayName("fee == commitment: CHARGE полный, UNBLOCK не создаётся (refund == 0)")
        void chargeForDiagnosticRequest_FeeEqualsCommitment_NoUnblockTransaction() {
            Deposit deposit = depositFor(new BigDecimal("20000"), new BigDecimal("10000"));
            when(depositRepository.findById(depositId)).thenReturn(Optional.of(deposit));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.chargeForDiagnosticRequest(depositId, requestId, new BigDecimal("10000"), new BigDecimal("10000"));

            ArgumentCaptor<DepositTransaction> captor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(captor.capture());
            assertEquals(DepositTransaction.TransactionType.CHARGE, captor.getValue().getType());
            assertEquals(0, captor.getValue().getAmount().compareTo(new BigDecimal("10000")));
        }

        @Test
        @DisplayName("fee == 0: UNBLOCK полный, CHARGE не создаётся")
        void chargeForDiagnosticRequest_ZeroFee_NoChargeTransaction() {
            Deposit deposit = depositFor(new BigDecimal("20000"), new BigDecimal("10000"));
            when(depositRepository.findById(depositId)).thenReturn(Optional.of(deposit));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.chargeForDiagnosticRequest(depositId, requestId, new BigDecimal("10000"), BigDecimal.ZERO);

            ArgumentCaptor<DepositTransaction> captor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(captor.capture());
            assertEquals(DepositTransaction.TransactionType.UNBLOCK, captor.getValue().getType());
            assertEquals(0, captor.getValue().getAmount().compareTo(new BigDecimal("10000")));
        }

        @Test
        @DisplayName("Депозит не найден — IllegalArgumentException, без мутаций")
        void chargeForDiagnosticRequest_DepositNotFound_Throws() {
            when(depositRepository.findById(depositId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () ->
                    depositService.chargeForDiagnosticRequest(depositId, requestId, new BigDecimal("10000"), new BigDecimal("4000")));

            verify(depositRepository, never()).save(any());
            verify(depositRepository, never()).saveTransaction(any());
        }
    }

    // ==================== X3-E corrective: chargeSellerViolationPenalty ledger reference ====================

    @Nested
    @DisplayName("X3-E corrective (BLOCKER 1): chargeSellerViolationPenalty() — ledger reference correctness")
    class ChargeSellerViolationPenaltyTests {

        private final Long requestId = 700L;
        private final Long realAuctionId = 900L;

        private Deposit depositFor(BigDecimal balance, BigDecimal blocked) {
            Deposit d = new Deposit(userId, balance);
            d.setId(depositId);
            if (blocked.compareTo(BigDecimal.ZERO) > 0) {
                d.freezeForAuction(blocked);
            }
            return d;
        }

        @Test
        @DisplayName("SALE_FAILED_C (real auctionId passed): CHARGE and UNBLOCK both carry the real Auction id, " +
                "never the diagnosticRequestId")
        void chargeSellerViolationPenalty_WithAuctionId_ChargeAndUnblockReferenceRealAuction() {
            Deposit deposit = depositFor(new BigDecimal("20000"), new BigDecimal("10000"));
            when(depositRepository.findById(depositId)).thenReturn(Optional.of(deposit));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.chargeSellerViolationPenalty(depositId, requestId, realAuctionId,
                    new BigDecimal("10000"), new BigDecimal("4000"));

            ArgumentCaptor<DepositTransaction> captor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(2)).saveTransaction(captor.capture());
            List<DepositTransaction> saved = captor.getAllValues();

            DepositTransaction charge = saved.stream()
                    .filter(t -> t.getType() == DepositTransaction.TransactionType.CHARGE).findFirst().orElseThrow();
            DepositTransaction unblock = saved.stream()
                    .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK).findFirst().orElseThrow();

            assertEquals(realAuctionId, charge.getAuctionId());
            assertEquals(realAuctionId, unblock.getAuctionId());
            assertNotEquals(requestId, charge.getAuctionId());
        }

        @Test
        @DisplayName("DIAGNOSTIC_REJECTION (auctionId=null passed): CHARGE and UNBLOCK both have auctionId=null - " +
                "no fabricated Auction id is ever created")
        void chargeSellerViolationPenalty_NullAuctionId_ChargeAndUnblockHaveNoFakeAuctionId() {
            Deposit deposit = depositFor(new BigDecimal("20000"), new BigDecimal("10000"));
            when(depositRepository.findById(depositId)).thenReturn(Optional.of(deposit));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.chargeSellerViolationPenalty(depositId, requestId, null,
                    new BigDecimal("10000"), new BigDecimal("4000"));

            ArgumentCaptor<DepositTransaction> captor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(2)).saveTransaction(captor.capture());
            List<DepositTransaction> saved = captor.getAllValues();

            assertThat(saved).allSatisfy(t -> assertThat(t.getAuctionId()).isNull());
        }

        @Test
        @DisplayName("diagnosticRequestId remains represented via reference (\"DIAGNOSTIC_\" + requestId) " +
                "regardless of whether a real auctionId is present")
        void chargeSellerViolationPenalty_ReferenceAlwaysCarriesDiagnosticRequestId() {
            Deposit deposit = depositFor(new BigDecimal("20000"), new BigDecimal("10000"));
            when(depositRepository.findById(depositId)).thenReturn(Optional.of(deposit));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.chargeSellerViolationPenalty(depositId, requestId, realAuctionId,
                    new BigDecimal("10000"), new BigDecimal("4000"));

            ArgumentCaptor<DepositTransaction> captor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(2)).saveTransaction(captor.capture());
            assertThat(captor.getAllValues()).allSatisfy(t ->
                    assertThat(t.getReference()).isEqualTo("DIAGNOSTIC_" + requestId));
        }

        @Test
        @DisplayName("Description text is the dedicated seller-violation wording, never chargeForDiagnosticRequest's " +
                "diagnostic-commission wording")
        void chargeSellerViolationPenalty_UsesDedicatedDescription_NeverDiagnosticFeeWording() {
            Deposit deposit = depositFor(new BigDecimal("20000"), new BigDecimal("10000"));
            when(depositRepository.findById(depositId)).thenReturn(Optional.of(deposit));
            when(depositRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(depositRepository.saveTransaction(any())).thenAnswer(i -> i.getArgument(0));

            depositService.chargeSellerViolationPenalty(depositId, requestId, realAuctionId,
                    new BigDecimal("10000"), new BigDecimal("4000"));

            ArgumentCaptor<DepositTransaction> captor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(2)).saveTransaction(captor.capture());
            assertThat(captor.getAllValues()).allSatisfy(t -> {
                assertThat(t.getDescription()).doesNotContain("диагностической комиссии");
                assertThat(t.getDescription()).contains("штрафа за нарушение");
            });
        }
    }

    // ==================== X3-A (SALE_FAILED outcomes): unblockForSaleFailure ====================

    @Nested
    @DisplayName("X3-A: разблокировка депозита победителя после SALE_FAILED (unblockForSaleFailure)")
    class UnblockForSaleFailureTests {

        private FrozenDeposit winnerFrozenDeposit() {
            FrozenDeposit frozen = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);
            frozen.markAsWinner();
            return frozen;
        }

        @Test
        @DisplayName("Корректный аукцион/победитель -> успешная разблокировка, UNBLOCK-транзакция записана")
        void unblockForSaleFailure_CorrectAuctionAndWinner_Succeeds() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId))
                    .thenReturn(List.of(winnerFrozenDeposit()));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            depositService.unblockForSaleFailure(auctionId, userId);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());
            assertEquals(0, depositCaptor.getValue().getBlockedAmount().compareTo(BigDecimal.ZERO));
            assertEquals(0, depositCaptor.getValue().getBalance().compareTo(depositAmount),
                    "balance не должен меняться - это unblock, не confiscate");

            ArgumentCaptor<DepositTransaction> txCaptor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(txCaptor.capture());
            assertEquals(DepositTransaction.TransactionType.UNBLOCK, txCaptor.getValue().getType());
            assertEquals(0, txCaptor.getValue().getAmount().compareTo(requiredDeposit));

            verify(depositRepository, times(1)).unfreezeForAuction(depositId, auctionId);
        }

        @Test
        @DisplayName("Неверный аукцион (frozen-запись для другого аукциона) -> IllegalStateException, без мутаций")
        void unblockForSaleFailure_WrongAuction_ThrowsWithoutMutation() {
            // given: frozen deposit существует, но для ДРУГОГО аукциона
            FrozenDeposit otherAuctionFrozen = new FrozenDeposit(999L, requiredDeposit, FreezeReason.FIRST_BID);
            otherAuctionFrozen.markAsWinner();
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId))
                    .thenReturn(List.of(otherAuctionFrozen));

            // when & then
            assertThrows(IllegalStateException.class,
                    () -> depositService.unblockForSaleFailure(auctionId, userId));
            verify(depositRepository, never()).save(any());
            verify(depositRepository, never()).saveTransaction(any());
        }

        @Test
        @DisplayName("Неверный победитель (frozen-запись не winner) -> IllegalStateException, без мутаций")
        void unblockForSaleFailure_NotWinnerFlagged_ThrowsWithoutMutation() {
            // given: frozen deposit для этого аукциона, но НЕ отмечен как winner
            FrozenDeposit nonWinnerFrozen = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId))
                    .thenReturn(List.of(nonWinnerFrozen));

            // when & then
            assertThrows(IllegalStateException.class,
                    () -> depositService.unblockForSaleFailure(auctionId, userId));
            verify(depositRepository, never()).save(any());
        }

        @Test
        @DisplayName("Нет замороженного депозита победителя -> IllegalStateException, без мутаций")
        void unblockForSaleFailure_NoFrozenDeposit_ThrowsWithoutMutation() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId)).thenReturn(List.of());

            assertThrows(IllegalStateException.class,
                    () -> depositService.unblockForSaleFailure(auctionId, userId));
            verify(depositRepository, never()).save(any());
        }

        @Test
        @DisplayName("Уже разблокировано (повторный вызов) -> IllegalStateException, без повторной мутации")
        void unblockForSaleFailure_AlreadyResolved_ThrowsWithoutDoubleMutation() {
            // given: первый вызов уже удалил frozen-запись - повторный находит пустой список
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId)).thenReturn(List.of());

            assertThrows(IllegalStateException.class,
                    () -> depositService.unblockForSaleFailure(auctionId, userId));
            verify(depositRepository, never()).save(any());
            verify(depositRepository, never()).saveTransaction(any());
        }

        @Test
        @DisplayName("Депозит не найден -> IllegalArgumentException")
        void unblockForSaleFailure_DepositNotFound_ThrowsIllegalArgumentException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.unblockForSaleFailure(auctionId, userId));
        }
    }

    // ==================== X3-A (SALE_FAILED outcome BUYER_FAULT): confiscateToPlatform ====================

    @Nested
    @DisplayName("X3-A: конфискация депозита победителя в пользу платформы (confiscateToPlatform)")
    class ConfiscateToPlatformTests {

        private FrozenDeposit winnerFrozenDeposit() {
            FrozenDeposit frozen = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);
            frozen.markAsWinner();
            return frozen;
        }

        @Test
        @DisplayName("Корректный аукцион/победитель -> balance и blockedAmount уменьшаются, CHARGE-транзакция записана")
        void confiscateToPlatform_CorrectAuctionAndWinner_DecreasesBalanceAndBlockedAmount() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId))
                    .thenReturn(List.of(winnerFrozenDeposit()));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BigDecimal balanceBefore = depositWithBlocked.getBalance();

            // when
            depositService.confiscateToPlatform(auctionId, userId);

            // then: никакие деньги не "создаются из воздуха" - balance уменьшается РОВНО на
            // сконфискованную сумму, blockedAmount уменьшается на ту же сумму.
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());
            assertEquals(0, depositCaptor.getValue().getBlockedAmount().compareTo(BigDecimal.ZERO));
            assertEquals(0, depositCaptor.getValue().getBalance().compareTo(balanceBefore.subtract(requiredDeposit)));

            ArgumentCaptor<DepositTransaction> txCaptor = ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(txCaptor.capture());
            assertEquals(DepositTransaction.TransactionType.CHARGE, txCaptor.getValue().getType());
            assertEquals(0, txCaptor.getValue().getAmount().compareTo(requiredDeposit));

            verify(depositRepository, times(1)).unfreezeForAuction(depositId, auctionId);
        }

        @Test
        @DisplayName("Неверный аукцион -> IllegalStateException, без мутаций")
        void confiscateToPlatform_WrongAuction_ThrowsWithoutMutation() {
            FrozenDeposit otherAuctionFrozen = new FrozenDeposit(999L, requiredDeposit, FreezeReason.FIRST_BID);
            otherAuctionFrozen.markAsWinner();
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId))
                    .thenReturn(List.of(otherAuctionFrozen));

            assertThrows(IllegalStateException.class,
                    () -> depositService.confiscateToPlatform(auctionId, userId));
            verify(depositRepository, never()).save(any());
            verify(depositRepository, never()).saveTransaction(any());
        }

        @Test
        @DisplayName("Неверный победитель (frozen-запись не winner) -> IllegalStateException, без мутаций")
        void confiscateToPlatform_NotWinnerFlagged_ThrowsWithoutMutation() {
            FrozenDeposit nonWinnerFrozen = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId))
                    .thenReturn(List.of(nonWinnerFrozen));

            assertThrows(IllegalStateException.class,
                    () -> depositService.confiscateToPlatform(auctionId, userId));
            verify(depositRepository, never()).save(any());
        }

        @Test
        @DisplayName("Нет замороженного депозита победителя -> IllegalStateException, без мутаций")
        void confiscateToPlatform_NoFrozenDeposit_ThrowsWithoutMutation() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId)).thenReturn(List.of());

            assertThrows(IllegalStateException.class,
                    () -> depositService.confiscateToPlatform(auctionId, userId));
            verify(depositRepository, never()).save(any());
        }

        @Test
        @DisplayName("Уже разрешено (повторный вызов) -> IllegalStateException, без повторной мутации")
        void confiscateToPlatform_AlreadyResolved_ThrowsWithoutDoubleMutation() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId)).thenReturn(List.of());

            assertThrows(IllegalStateException.class,
                    () -> depositService.confiscateToPlatform(auctionId, userId));
            verify(depositRepository, never()).save(any());
            verify(depositRepository, never()).saveTransaction(any());
        }

        @Test
        @DisplayName("Депозит не найден -> IllegalArgumentException")
        void confiscateToPlatform_DepositNotFound_ThrowsIllegalArgumentException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.confiscateToPlatform(auctionId, userId));
        }
    }
}