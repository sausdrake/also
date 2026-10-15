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

    /**
     * D4 defect fix: forces the write (and its {@code @Version} check) to happen synchronously,
     * inside the caller's own try/catch, instead of being deferred to transaction commit. Needed
     * specifically by {@code DepositService.unfreezeForAuction} - see its own Javadoc - where a
     * plain {@link #save(Deposit)} lets a genuine optimistic-lock conflict surface only at
     * {@code JpaTransactionManager.doCommit}, after the method body (and its catch block) has
     * already returned, so the failure escapes uncaught instead of being recorded via
     * {@code FailedOperationService}.
     */
    Deposit saveAndFlush(Deposit deposit);

    Optional<Deposit> findById(Long id);
    Optional<Deposit> findByUserId(Long userId);

    /**
     * P1.5: pessimistic ({@code SELECT ... FOR UPDATE}) load of the user's Deposit row, used as
     * the serialization primitive for payment-creation capacity checks (creation-vs-creation for
     * the same user). Never held across an outbound provider call. Mirrors this project's
     * {@code findByIdForUpdate}/{@code findByIdWithLock} idiom (Payment, Identity) - explicitly
     * {@code @Transactional} on the adapter method itself, not just relying on the class-level
     * annotation, per the lesson already proven twice in this engagement (a lock query with no
     * transactional advice on its own proxy boundary silently loses its serialization power).
     */
    Optional<Deposit> findByUserIdForUpdate(Long userId);
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

    /**
     * F7: the RECONCILIATION admin adjustment already booked for this external operation
     * reference, if any (at most one - V56 partial unique index).
     */
    Optional<DepositTransaction> findReconciliationAdjustmentByReference(String reference);

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