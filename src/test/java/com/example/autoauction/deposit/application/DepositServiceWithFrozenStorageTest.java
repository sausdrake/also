// deposit/application/DepositServiceWithFrozenStorageTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.common.application.FailedOperationService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.FreezeReason;
import com.example.autoauction.deposit.domain.FrozenDeposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты DepositService с хранением заморозок в БД")
class DepositServiceWithFrozenStorageTest {

    @Mock
    private DepositRepository depositRepository;

    @Mock
    private FailedOperationService failedOperationService;

    @InjectMocks
    private DepositService depositService;

    private Deposit deposit;
    private final Long userId = 1L;
    private final Long auctionId = 100L;
    private final Long depositId = 10L;
    private final BigDecimal startingPrice = new BigDecimal("1000000");
    private final BigDecimal requiredDeposit = new BigDecimal("10000.00");
    private final BigDecimal depositBalance = new BigDecimal("50000");

    @BeforeEach
    void setUp() {
        deposit = new Deposit(userId, depositBalance);
        deposit.setId(depositId);
    }

    @Test
    @DisplayName("Заморозка депозита - сохраняется в БД")
    void freezeForAuction_ShouldSaveToDatabase() {
        // given
        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));
        when(depositRepository.isFrozenForAuction(depositId, auctionId)).thenReturn(false);
        when(depositRepository.save(any(Deposit.class))).thenReturn(deposit);
        when(depositRepository.saveTransaction(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        depositService.freezeForAuction(userId, auctionId, startingPrice);

        // then
        ArgumentCaptor<Long> depositIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> auctionIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<FreezeReason> reasonCaptor = ArgumentCaptor.forClass(FreezeReason.class);

        verify(depositRepository).freezeForAuction(
                depositIdCaptor.capture(),
                auctionIdCaptor.capture(),
                amountCaptor.capture(),
                reasonCaptor.capture()
        );

        assertThat(depositIdCaptor.getValue()).isEqualTo(depositId);
        assertThat(auctionIdCaptor.getValue()).isEqualTo(auctionId);
        assertThat(amountCaptor.getValue()).isEqualTo(requiredDeposit);
        assertThat(reasonCaptor.getValue()).isEqualTo(FreezeReason.FIRST_BID);
    }

    @Test
    @DisplayName("Разморозка депозита - удаляется из БД")
    void unfreezeForAuction_ShouldDeleteFromDatabase() {
        // given - сначала замораживаем депозит
        deposit.freezeForAuction(requiredDeposit); // добавляем блокировку в домен
        FrozenDeposit frozenDeposit = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);

        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));
        when(depositRepository.getFrozenDepositsByDepositId(depositId))
                .thenReturn(List.of(frozenDeposit));
        doNothing().when(depositRepository).unfreezeForAuction(depositId, auctionId);
        when(depositRepository.save(any(Deposit.class))).thenReturn(deposit);
        when(depositRepository.saveTransaction(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        depositService.unfreezeForAuction(userId, auctionId);

        // then
        verify(depositRepository, times(1)).unfreezeForAuction(depositId, auctionId);
    }

    @Test
    @DisplayName("Попытка разморозки без заморозки в БД - метод не делает ничего (не выбрасывает исключение)")
    void unfreezeForAuction_NoFrozenAmountInDb_ShouldDoNothing() {
        // given - депозит имеет заблокированные средства в домене
        deposit.freezeForAuction(requiredDeposit);

        // но в БД нет записи о заморозке (возвращаем пустой список)
        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));
        when(depositRepository.getFrozenDepositsByDepositId(depositId))
                .thenReturn(List.of()); // пустой список - нет записи в БД

        // when - метод не должен выбрасывать исключение
        assertDoesNotThrow(() -> depositService.unfreezeForAuction(userId, auctionId));

        verify(depositRepository, never()).unfreezeForAuction(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Попытка разморозки без заблокированных средств в домене - метод не делает ничего")
    void unfreezeForAuction_NoFrozenAmountInDomain_ShouldDoNothing() {
        // given - депозит НЕ имеет заблокированных средств, но в БД есть запись
        FrozenDeposit frozenDeposit = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);

        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));
        when(depositRepository.getFrozenDepositsByDepositId(depositId))
                .thenReturn(List.of(frozenDeposit));

        // when - метод должен обработать ситуацию без исключения
        assertDoesNotThrow(() -> depositService.unfreezeForAuction(userId, auctionId));
    }

    @Test
    @DisplayName("Попытка заморозки с null startingPrice - исключение")
    void freezeForAuction_NullStartingPrice_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> depositService.freezeForAuction(userId, auctionId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Starting price must be positive");

        verify(depositRepository, never()).freezeForAuction(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Повторная заморозка для того же аукциона - игнорируется")
    void freezeForAuction_AlreadyFrozen_ShouldSkip() {
        // given
        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));
        when(depositRepository.isFrozenForAuction(depositId, auctionId)).thenReturn(true);

        // when
        depositService.freezeForAuction(userId, auctionId, startingPrice);

        // then
        verify(depositRepository, never()).freezeForAuction(anyLong(), anyLong(), any(), any());
        verify(depositRepository, never()).save(any(Deposit.class));
    }

    @Test
    @DisplayName("Заморозка при недостаточном балансе - исключение")
    void freezeForAuction_InsufficientBalance_ThrowsException() {
        // given
        Deposit poorDeposit = new Deposit(userId, new BigDecimal("5000")); // всего 5000, надо 10000
        poorDeposit.setId(depositId);

        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(poorDeposit));
        when(depositRepository.isFrozenForAuction(depositId, auctionId)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> depositService.freezeForAuction(userId, auctionId, startingPrice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Недостаточно средств");

        verify(depositRepository, never()).freezeForAuction(anyLong(), anyLong(), any(), any());
    }
}