// deposit/domain/port/DepositRepository.java
package com.example.autoauction.deposit.domain.port;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.FreezeReason;
import com.example.autoauction.deposit.domain.FrozenDeposit;

import java.util.List;
import java.util.Optional;

public interface DepositRepository {
    // Депозиты
    Deposit save(Deposit deposit);
    Optional<Deposit> findById(Long id);
    Optional<Deposit> findByUserId(Long userId);
    List<Deposit> findAll();
    void deleteById(Long id);

    // Транзакции
    DepositTransaction saveTransaction(DepositTransaction transaction);
    List<DepositTransaction> findTransactionsByUserId(Long userId);
    List<DepositTransaction> findTransactionsByUserIdFiltered(
            Long userId,
            DepositTransaction.TransactionType type,
            java.time.OffsetDateTime from,
            java.time.OffsetDateTime to);
    List<DepositTransaction> findTransactionsByDepositId(Long depositId);
    List<DepositTransaction> findTransactionsByAuctionId(Long auctionId);
    Optional<DepositTransaction> findTransactionByIdempotencyKey(String idempotencyKey);

    // НОВЫЕ МЕТОДЫ для работы с frozen deposits
    void freezeForAuction(Long depositId, Long auctionId, java.math.BigDecimal amount, FreezeReason reason);
    void unfreezeForAuction(Long depositId, Long auctionId);
    void markFrozenAsWinner(Long depositId, Long auctionId);
    List<FrozenDeposit> getFrozenDepositsByDepositId(Long depositId);
    List<FrozenDeposit> getFrozenDepositsByAuctionId(Long auctionId);
    boolean isFrozenForAuction(Long depositId, Long auctionId);
    /** True if a frozen record exists for this (depositId, auctionId) and is NOT yet marked winner. */
    boolean needsWinnerMark(Long depositId, Long auctionId);
    void clearFrozenForAuction(Long auctionId);
}