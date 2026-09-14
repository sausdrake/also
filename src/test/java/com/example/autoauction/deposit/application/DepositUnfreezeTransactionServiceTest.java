// deposit/application/DepositUnfreezeTransactionServiceTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D4 Option B: unit coverage for the isolated Deposit/FrozenDeposit/ledger mutation extracted out
 * of DepositService.unfreezeForAuction. Deliberately plain Mockito - proxy/transaction-boundary
 * behavior is covered separately by DepositCreditVsMutatorsConcurrencyPgIntegrationTest against
 * real PostgreSQL/Spring; this class only proves the mutation's own arithmetic and call shape.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DepositUnfreezeTransactionService — изолированная транзакция размораживания")
class DepositUnfreezeTransactionServiceTest {

    @Mock
    private DepositRepository depositRepository;

    @InjectMocks
    private DepositUnfreezeTransactionService service;

    private final Long userId = 1L;
    private final Long auctionId = 100L;
    private final Long depositId = 10L;
    private final BigDecimal requiredDeposit = new BigDecimal("10000.00");
    private final BigDecimal depositAmount = new BigDecimal("50000");

    private Deposit depositWithBlocked;

    @BeforeEach
    void setUp() {
        depositWithBlocked = new Deposit(userId, depositAmount);
        depositWithBlocked.setId(depositId);
        depositWithBlocked.freezeForAuction(requiredDeposit);
    }

    @Test
    @DisplayName("A. Успешная разморозка: корректная мутация Deposit, UNBLOCK, удаление FrozenDeposit, saveAndFlush, возврат суммы")
    void unfreeze_Success_MutatesCorrectlyAndReturnsAmount() {
        FrozenDeposit frozenDeposit = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);
        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
        when(depositRepository.getFrozenDepositsByDepositId(depositId)).thenReturn(List.of(frozenDeposit));
        when(depositRepository.saveAndFlush(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(depositRepository.saveTransaction(any(DepositTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<BigDecimal> result = service.unfreeze(userId, auctionId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo(requiredDeposit);

        verify(depositRepository).unfreezeForAuction(depositId, auctionId);

        ArgumentCaptor<DepositTransaction> txCaptor = ArgumentCaptor.forClass(DepositTransaction.class);
        verify(depositRepository).saveTransaction(txCaptor.capture());
        DepositTransaction tx = txCaptor.getValue();
        assertThat(tx.getType()).isEqualTo(DepositTransaction.TransactionType.UNBLOCK);
        assertThat(tx.getAmount()).isEqualByComparingTo(requiredDeposit);
        assertThat(tx.getAuctionId()).isEqualTo(auctionId);

        ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
        verify(depositRepository).saveAndFlush(depositCaptor.capture());
        Deposit saved = depositCaptor.getValue();
        assertThat(saved.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getAvailableBalance()).isEqualByComparingTo(depositAmount);
    }

    @Test
    @DisplayName("B. Нет соответствующего FrozenDeposit: Optional.empty(), без мутации/ledger/saveAndFlush")
    void unfreeze_NoMatchingFrozenDeposit_ReturnsEmptyWithNoMutation() {
        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
        when(depositRepository.getFrozenDepositsByDepositId(depositId)).thenReturn(List.of());

        Optional<BigDecimal> result = service.unfreeze(userId, auctionId);

        assertThat(result).isEmpty();
        verify(depositRepository, never()).unfreezeForAuction(any(), any());
        verify(depositRepository, never()).saveTransaction(any());
        verify(depositRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("C. Депозит не найден: исключение пробрасывается наружу, без обработки FailedOperation здесь")
    void unfreeze_DepositNotFound_PropagatesException() {
        when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unfreeze(userId, auctionId))
                .isInstanceOf(IllegalArgumentException.class);

        verify(depositRepository, never()).saveTransaction(any());
        verify(depositRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("C. Сбой репозитория при сохранении ledger: исключение пробрасывается наружу как есть")
    void unfreeze_RepositoryFailure_PropagatesExceptionUnchanged() {
        FrozenDeposit frozenDeposit = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);
        when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
        when(depositRepository.getFrozenDepositsByDepositId(depositId)).thenReturn(List.of(frozenDeposit));
        when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.unfreeze(userId, auctionId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");

        verify(depositRepository, never()).saveAndFlush(any());
    }
}
