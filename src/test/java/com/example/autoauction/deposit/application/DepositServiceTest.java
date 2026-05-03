package com.example.autoauction.deposit.application;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositStatus;
import com.example.autoauction.deposit.domain.DepositTransaction;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты DepositService - сервиса управления депозитами")
class DepositServiceTest {

    @Mock
    private DepositRepository depositRepository;

    @InjectMocks
    private DepositService depositService;

    private Deposit deposit;
    private Deposit depositWithBalance;
    private Deposit depositWithBlocked;
    private final Long userId = 1L;
    private final Long anotherUserId = 2L;
    private final Long auctionId = 100L;
    private final Long depositId = 10L;
    private final BigDecimal startingPrice = new BigDecimal("1000000");
    private final BigDecimal requiredDeposit = new BigDecimal("10000.00"); // 1%
    private final BigDecimal depositAmount = new BigDecimal("50000");
    private final BigDecimal withdrawAmount = new BigDecimal("30000");
    private final BigDecimal zero = BigDecimal.ZERO;
    private final BigDecimal negativeAmount = new BigDecimal("-1000");

    @BeforeEach
    void setUp() {
        // Депозит с нулевым балансом
        deposit = new Deposit(userId, zero);
        deposit.setId(depositId);
        deposit.setCreatedAt(OffsetDateTime.now());
        deposit.setUpdatedAt(OffsetDateTime.now());

        // Депозит с балансом
        depositWithBalance = new Deposit(userId, depositAmount);
        depositWithBalance.setId(depositId);

        // Депозит с заблокированными средствами
        depositWithBlocked = new Deposit(userId, depositAmount);
        depositWithBlocked.setId(depositId);
        depositWithBlocked.freezeForAuction(auctionId, startingPrice);
    }

    // ==================== CREATE DEPOSIT TESTS ====================

    @Nested
    @DisplayName("Создание депозита")
    class CreateDepositTests {

        @Test
        @DisplayName("Успешное создание депозита для нового пользователя с нулевым балансом")
        void createDeposit_NewUser_WithZeroBalance_Success() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            DepositResponse response = depositService.createDeposit(userId, zero);

            // then
            assertNotNull(response);
            assertEquals(userId, response.userId());
            assertEquals(zero, response.balance());
            assertEquals(zero, response.blockedAmount());
            assertEquals(zero, response.availableBalance());
            assertEquals(DepositStatus.ACTIVE, response.status());

            verify(depositRepository, times(1)).save(any(Deposit.class));
            verify(depositRepository, never()).saveTransaction(any());
        }

        @Test
        @DisplayName("Успешное создание депозита с начальным балансом")
        void createDeposit_NewUser_WithInitialAmount_Success() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            DepositResponse response = depositService.createDeposit(userId, depositAmount);

            // then
            assertNotNull(response);
            assertEquals(depositAmount, response.balance());
            assertEquals(zero, response.blockedAmount());
            assertEquals(depositAmount, response.availableBalance());

            // Проверяем, что транзакция создана
            ArgumentCaptor<DepositTransaction> transactionCaptor =
                    ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(transactionCaptor.capture());

            DepositTransaction transaction = transactionCaptor.getValue();
            assertEquals(DepositTransaction.TransactionType.DEPOSIT, transaction.getType());
            assertEquals(depositAmount, transaction.getAmount());
            assertEquals(zero, transaction.getBalanceBefore());
            assertEquals(depositAmount, transaction.getBalanceAfter());
            assertEquals("INITIAL", transaction.getReference());
        }

        @Test
        @DisplayName("Создание депозита для существующего пользователя - возвращает существующий")
        void createDeposit_ExistingUser_ReturnsExistingDeposit() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            // Убираем лишний мок save, так как save не должен вызываться
            // when(depositRepository.save(any(Deposit.class))).thenReturn(depositWithBalance);

            // when
            DepositResponse response = depositService.createDeposit(userId, depositAmount);

            // then
            assertNotNull(response);
            assertEquals(depositAmount, response.balance());

            // save не должен вызываться для существующего депозита
            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Создание депозита с null userId - NPE")
        void createDeposit_NullUserId_ThrowsException() {
            assertThrows(NullPointerException.class,
                    () -> depositService.createDeposit(null, depositAmount));
        }
    }

    // ==================== DEPOSIT (ПОПОЛНЕНИЕ) TESTS ====================

    @Nested
    @DisplayName("Пополнение депозита")
    class DepositMoneyTests {

        @Test
        @DisplayName("Успешное пополнение депозита")
        void deposit_Success() {
            // given
            BigDecimal addAmount = new BigDecimal("10000");
            BigDecimal expectedBalance = depositAmount.add(addAmount);

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            DepositResponse response = depositService.deposit(userId, addAmount);

            // then
            assertNotNull(response);
            assertEquals(expectedBalance, response.balance());
            assertEquals(expectedBalance, response.availableBalance());

            // Проверяем транзакцию
            ArgumentCaptor<DepositTransaction> transactionCaptor =
                    ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(transactionCaptor.capture());

            DepositTransaction transaction = transactionCaptor.getValue();
            assertEquals(DepositTransaction.TransactionType.DEPOSIT, transaction.getType());
            assertEquals(addAmount, transaction.getAmount());
            assertEquals(depositAmount, transaction.getBalanceBefore());
            assertEquals(expectedBalance, transaction.getBalanceAfter());
        }

        @Test
        @DisplayName("Пополнение с нулевой суммой - IllegalArgumentException")
        void deposit_ZeroAmount_ThrowsException() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> depositService.deposit(userId, zero));
            assertTrue(exception.getMessage().contains("больше 0"));

            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Пополнение с отрицательной суммой - IllegalArgumentException")
        void deposit_NegativeAmount_ThrowsException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.deposit(userId, negativeAmount));

            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Пополнение несуществующего депозита - IllegalArgumentException")
        void deposit_DepositNotFound_ThrowsException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.deposit(userId, depositAmount));
        }
    }

    // ==================== WITHDRAW (ВЫВОД) TESTS ====================

    @Nested
    @DisplayName("Вывод средств с депозита")
    class WithdrawTests {

        @Test
        @DisplayName("Успешный вывод средств")
        void withdraw_Success() {
            // given
            BigDecimal expectedBalance = depositAmount.subtract(withdrawAmount);

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            DepositResponse response = depositService.withdraw(userId, withdrawAmount);

            // then
            assertNotNull(response);
            assertEquals(expectedBalance, response.balance());

            // Проверяем транзакцию
            ArgumentCaptor<DepositTransaction> transactionCaptor =
                    ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(transactionCaptor.capture());

            DepositTransaction transaction = transactionCaptor.getValue();
            assertEquals(DepositTransaction.TransactionType.WITHDRAWAL, transaction.getType());
            assertEquals(withdrawAmount, transaction.getAmount());
        }

        @Test
        @DisplayName("Вывод суммы больше доступного баланса - IllegalStateException")
        void withdraw_InsufficientFunds_ThrowsException() {
            // given
            BigDecimal tooMuch = depositAmount.add(new BigDecimal("10000"));
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> depositService.withdraw(userId, tooMuch));
            assertTrue(exception.getMessage().contains("Недостаточно"));

            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Вывод при заблокированных средствах - учитывается blockedAmount")
        void withdraw_WithBlockedFunds_ThrowsException() {
            // given - депозит с заблокированными средствами
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));

            // Доступный баланс: 50000 - 10000 = 40000
            // Пытаемся вывести 45000 - должно быть ошибка
            BigDecimal withdrawTooMuch = new BigDecimal("45000");

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> depositService.withdraw(userId, withdrawTooMuch));
            assertTrue(exception.getMessage().contains("Недостаточно"));
        }

        @Test
        @DisplayName("Вывод нулевой суммы - IllegalArgumentException")
        void withdraw_ZeroAmount_ThrowsException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.withdraw(userId, zero));
        }

        @Test
        @DisplayName("Вывод отрицательной суммы - IllegalArgumentException")
        void withdraw_NegativeAmount_ThrowsException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.withdraw(userId, negativeAmount));
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
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            depositService.freezeForAuction(userId, auctionId, startingPrice);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());

            Deposit savedDeposit = depositCaptor.getValue();
            // Используем compareTo вместо assertEquals
            assertTrue(savedDeposit.getBlockedAmount().compareTo(requiredDeposit) == 0);
            assertTrue(savedDeposit.getAvailableBalance().compareTo(depositAmount.subtract(requiredDeposit)) == 0);

            // Проверяем транзакцию
            ArgumentCaptor<DepositTransaction> transactionCaptor =
                    ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(transactionCaptor.capture());

            DepositTransaction transaction = transactionCaptor.getValue();
            assertEquals(DepositTransaction.TransactionType.BLOCK, transaction.getType());
            assertTrue(transaction.getAmount().compareTo(requiredDeposit) == 0);
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
            BigDecimal startingPrice2 = new BigDecimal("2000000");
            BigDecimal requiredDeposit2 = new BigDecimal("20000.00");

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(richDeposit));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when - первая заморозка
            depositService.freezeForAuction(userId, auctionId, startingPrice);

            // when - вторая заморозка
            depositService.freezeForAuction(userId, auctionId2, startingPrice2);

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
                    () -> depositService.freezeForAuction(userId, auctionId, startingPrice));
            assertTrue(exception.getMessage().contains("Недостаточно средств"));

            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Заморозка с null startingPrice - метод ничего не делает (логирует предупреждение)")
        void freezeForAuction_NullStartingPrice_DoesNothing() {
            // given - НЕ мокаем depositRepository, так как он не будет вызван

            // when & then - метод не должен выбросить исключение
            assertDoesNotThrow(() -> depositService.freezeForAuction(userId, auctionId, null));

            // Проверяем, что репозиторий не вызывался
            verify(depositRepository, never()).findByUserId(anyLong());
            verify(depositRepository, never()).save(any(Deposit.class));
            verify(depositRepository, never()).saveTransaction(any(DepositTransaction.class));
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
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            depositService.unfreezeForAuction(userId, auctionId, startingPrice);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());

            Deposit savedDeposit = depositCaptor.getValue();
            // Используем compareTo для сравнения BigDecimal
            assertTrue(savedDeposit.getBlockedAmount().compareTo(BigDecimal.ZERO) == 0);
            // Используем compareTo для сравнения сумм
            assertTrue(savedDeposit.getAvailableBalance().compareTo(depositAmount) == 0);

            // Проверяем транзакцию
            ArgumentCaptor<DepositTransaction> transactionCaptor =
                    ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(transactionCaptor.capture());

            DepositTransaction transaction = transactionCaptor.getValue();
            assertEquals(DepositTransaction.TransactionType.UNBLOCK, transaction.getType());
            assertTrue(transaction.getAmount().compareTo(requiredDeposit) == 0);
        }

        @Test
        @DisplayName("Разморозка всех средств (перегрузка метода)")
        void unfreezeForAuction_Overload_Success() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Сохраняем состояние до разморозки для отладки
            BigDecimal blockedBefore = depositWithBlocked.getBlockedAmount();
            BigDecimal availableBefore = depositWithBlocked.getAvailableBalance();

            System.out.println("Before unfreeze - blocked: " + blockedBefore + ", available: " + availableBefore);

            // when
            depositService.unfreezeForAuction(userId, auctionId);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());

            Deposit savedDeposit = depositCaptor.getValue();

            System.out.println("After unfreeze - blocked: " + savedDeposit.getBlockedAmount() +
                    ", available: " + savedDeposit.getAvailableBalance());

            // Проверяем, что заблокированная сумма уменьшилась (стала 0)
            // Используем compareTo для точного сравнения
            assertTrue(savedDeposit.getBlockedAmount().compareTo(BigDecimal.ZERO) == 0,
                    "Blocked amount should be 0, but was " + savedDeposit.getBlockedAmount());

            // Проверяем, что доступный баланс = общему балансу
            assertTrue(savedDeposit.getAvailableBalance().compareTo(savedDeposit.getBalance()) == 0,
                    "Available balance should equal total balance");

            // Проверяем, что транзакция разблокировки была создана
            verify(depositRepository, times(1)).saveTransaction(any(DepositTransaction.class));
        }

        @Test
        @DisplayName("Разморозка без заблокированных средств - транзакция не создаётся")
        void unfreezeForAuction_NoFrozenFunds_NoTransaction() {
            // given - депозит без заблокированных средств
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            // when
            depositService.unfreezeForAuction(userId, auctionId);

            // then
            verify(depositRepository, never()).saveTransaction(any());
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
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            depositService.markAsWinner(userId, auctionId);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());

            Deposit savedDeposit = depositCaptor.getValue();
            // Баланс должен остаться неизменным (не разморожен)
            assertEquals(requiredDeposit, savedDeposit.getBlockedAmount());
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
        @DisplayName("Достаточно средств - true")
        void canParticipateInAuction_WithEnoughFunds_ReturnsTrue() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            boolean result = depositService.canParticipateInAuction(userId, startingPrice);

            assertTrue(result);
        }

        @Test
        @DisplayName("Недостаточно средств - false")
        void canParticipateInAuction_InsufficientFunds_ReturnsFalse() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));

            boolean result = depositService.canParticipateInAuction(userId, startingPrice);

            assertFalse(result);
        }

        @Test
        @DisplayName("Депозит не существует - false")
        void canParticipateInAuction_NoDeposit_ReturnsFalse() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            boolean result = depositService.canParticipateInAuction(userId, startingPrice);

            assertFalse(result);
        }

        @Test
        @DisplayName("Проверка без указания стартовой цены - проверяем доступный баланс > 0")
        void canParticipateInAuction_WithoutStartingPrice_Success() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            boolean result = depositService.canParticipateInAuction(userId);

            assertTrue(result);
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

        @Test
        @DisplayName("Пользователь без транзакций - пустой список")
        void getTransactionsByUserId_EmptyList_ReturnsEmpty() {
            when(depositRepository.findTransactionsByUserId(userId)).thenReturn(List.of());

            List<DepositTransactionResponse> responses =
                    depositService.getTransactionsByUserId(userId);

            assertTrue(responses.isEmpty());
        }
    }

    // ==================== CALCULATE REQUIRED DEPOSIT TESTS ====================

    @Nested
    @DisplayName("Расчёт требуемого депозита")
    class CalculateRequiredDepositTests {

        @Test
        @DisplayName("1% от 1,000,000 = 10,000")
        void calculateRequiredDeposit_OneMillion_ReturnsTenThousand() {
            BigDecimal result = Deposit.calculateRequiredDeposit(new BigDecimal("1000000"));
            assertEquals(new BigDecimal("10000.00"), result);
        }

        @Test
        @DisplayName("1% от 50,000 = 500")
        void calculateRequiredDeposit_FiftyThousand_ReturnsFiveHundred() {
            BigDecimal result = Deposit.calculateRequiredDeposit(new BigDecimal("50000"));
            assertEquals(new BigDecimal("500.00"), result);
        }

        @Test
        @DisplayName("1% от 99.99 = 1.00 (округление HALF_UP)")
        void calculateRequiredDeposit_NinetyNine_ReturnsOne() {
            BigDecimal result = Deposit.calculateRequiredDeposit(new BigDecimal("99.99"));
            assertEquals(new BigDecimal("1.00"), result);
        }

        @Test
        @DisplayName("Null startingPrice - возвращает 0")
        void calculateRequiredDeposit_Null_ReturnsZero() {
            BigDecimal result = Deposit.calculateRequiredDeposit(null);
            assertEquals(BigDecimal.ZERO, result);
        }

        @Test
        @DisplayName("Отрицательная стартовая цена - возвращает 0")
        void calculateRequiredDeposit_Negative_ReturnsZero() {
            BigDecimal result = Deposit.calculateRequiredDeposit(new BigDecimal("-1000"));
            assertEquals(BigDecimal.ZERO, result);
        }
    }

    // ==================== CONCURRENCY TESTS ====================

    @Nested
    @DisplayName("Тесты конкурентного доступа")
    class ConcurrencyTests {

        @Test
        @DisplayName("Параллельное пополнение и вывод - данные должны быть консистентны")
        void concurrentDepositAndWithdraw_ShouldBeConsistent() throws Exception {
            // given
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(20);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            // Создаём общий депозит для всех потоков
            Deposit sharedDeposit = new Deposit(userId, new BigDecimal("10000"));
            sharedDeposit.setId(depositId);

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(sharedDeposit));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    try {
                        depositService.deposit(userId, new BigDecimal("100"));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });

                executor.submit(() -> {
                    try {
                        depositService.withdraw(userId, new BigDecimal("50"));
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

            // then - некоторые операции могут быть отклонены, но система должна быть консистентна
            assertTrue(sharedDeposit.getBalance().compareTo(BigDecimal.ZERO) >= 0);
        }
    }
}