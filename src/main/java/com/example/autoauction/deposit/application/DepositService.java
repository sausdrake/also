// deposit/application/DepositService.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.common.application.FailedOperationService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.FreezeReason;
import com.example.autoauction.deposit.domain.FrozenDeposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.deposit.web.dto.DepositTransactionResponse;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DepositService {

    private final DepositRepository depositRepository;
    private final FailedOperationService failedOperationService;
    private final NotificationService notificationService;

    public DepositService(DepositRepository depositRepository,
                          FailedOperationService failedOperationService,
                          NotificationService notificationService) {
        this.depositRepository = depositRepository;
        this.failedOperationService = failedOperationService;
        this.notificationService = notificationService;
        log.info("DepositService initialized");
    }

    @Transactional
    public DepositResponse createDeposit(Long userId, BigDecimal initialAmount) {
        log.info("Creating deposit for user: {} with amount: {}", userId, initialAmount);

        if (depositRepository.findByUserId(userId).isPresent()) {
            log.info("Deposit already exists for user: {}", userId);
            return getDepositByUserId(userId);
        }

        Deposit deposit = new Deposit(userId, initialAmount);
        Deposit saved = depositRepository.save(deposit);

        if (initialAmount.compareTo(BigDecimal.ZERO) > 0) {
            DepositTransaction transaction = new DepositTransaction(
                    saved.getId(),
                    userId,
                    DepositTransaction.TransactionType.DEPOSIT,
                    initialAmount,
                    BigDecimal.ZERO,
                    initialAmount,
                    "INITIAL",
                    "Начальное пополнение депозита"
            );
            depositRepository.saveTransaction(transaction);
        }

        log.info("Deposit created successfully for user: {}, balance: {}", userId, saved.getBalance());
        return DepositResponse.fromDomain(saved);
    }

    /**
     * Самостоятельное пополнение депозита пользователем отключено: платформа не имеет
     * подключённой платёжной интеграции для верификации реального перевода денег.
     * Зачисление выполняется администратором через {@link #adminAdjust} после проверки платежа.
     */
    @Transactional
    public DepositResponse deposit(Long userId, BigDecimal amount) {
        log.warn("Rejected self-service deposit attempt by user {}: amount={}", userId, amount);
        throw new IllegalStateException(
                "Самостоятельное пополнение депозита недоступно. " +
                "Зачисление средств выполняется администратором после проверки платежа.");
    }

    /**
     * R-1.3: idempotencyKey обязателен. Клиент генерирует его один раз на попытку вывода
     * и переиспользует при повторной отправке того же запроса (например, после сетевой
     * ошибки/потери ответа) — повтор с тем же ключом возвращает уже применённый результат,
     * не выполняя повторное списание.
     */
    @Transactional
    public DepositResponse withdraw(Long userId, BigDecimal amount, String idempotencyKey) {
        log.info("Withdraw for user: {}, amount: {}", userId, amount);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Ключ идемпотентности обязателен для вывода средств");
        }

        Optional<DepositTransaction> existing = depositRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            DepositTransaction prev = existing.get();
            boolean sameFingerprint = prev.getUserId().equals(userId)
                    && prev.getType() == DepositTransaction.TransactionType.WITHDRAWAL
                    && amount != null && prev.getAmount().compareTo(amount) == 0;
            if (!sameFingerprint) {
                throw new IllegalStateException(
                        "Ключ идемпотентности '" + idempotencyKey + "' уже использован для другой операции " +
                        "(другая сумма, пользователь или тип операции)");
            }
            log.info("Idempotent replay of withdrawal for key '{}', user {}. No new mutation applied.",
                    idempotencyKey, userId);
            return getDepositByUserId(userId);
        }

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        BigDecimal balanceBefore = deposit.getBalance();
        deposit.withdraw(amount);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                userId,
                DepositTransaction.TransactionType.WITHDRAWAL,
                amount,
                balanceBefore,
                deposit.getBalance(),
                "WITHDRAWAL",
                "Вывод средств с депозита"
        );
        transaction.setIdempotencyKey(idempotencyKey);
        // Уникальность idempotency_key обеспечена на уровне БД (тот же частичный уникальный
        // индекс V21, что и для adminAdjust — конкурентный дубликат с тем же ключом
        // завершится DataIntegrityViolationException и полным откатом транзакции).
        depositRepository.saveTransaction(transaction);

        Deposit saved = depositRepository.save(deposit);
        log.info("Withdraw successful. New balance: {}", saved.getBalance());
        return DepositResponse.fromDomain(saved);
    }

    @Transactional
    public void freezeForAuction(Long userId, Long auctionId, BigDecimal requiredAmount) {
        log.info("Freezing deposit for user: {}, auction: {}", userId, auctionId);

        if (requiredAmount == null || requiredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid requiredAmount: {}. Cannot freeze deposit.", requiredAmount);
            throw new IllegalArgumentException("Required deposit amount must be positive");
        }

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        if (depositRepository.isFrozenForAuction(deposit.getId(), auctionId)) {
            log.warn("Deposit already frozen for auction {}", auctionId);
            return;
        }

        BigDecimal balanceBefore = deposit.getAvailableBalance();

        // ИСПРАВЛЕНО: передаём только requiredAmount, а не auctionId
        deposit.freezeForAuction(requiredAmount);

        depositRepository.freezeForAuction(deposit.getId(), auctionId, requiredAmount, FreezeReason.FIRST_BID);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                userId,
                DepositTransaction.TransactionType.BLOCK,
                requiredAmount,
                balanceBefore,
                deposit.getAvailableBalance(),
                auctionId.toString(),
                "Блокировка средств для участия в аукционе #" + auctionId
        );
        transaction.setAuctionId(auctionId);
        depositRepository.saveTransaction(transaction);

        depositRepository.save(deposit);
        log.info("Deposit frozen. New available balance: {}", deposit.getAvailableBalance());

        try {
            notificationService.send(userId, "DEPOSIT_FROZEN",
                    "Депозит заморожен",
                    String.format("Для участия в аукционе #%d заморожено %s сом.",
                            auctionId, requiredAmount.toPlainString()));
        } catch (Exception e) {
            log.warn("Failed to send deposit freeze notification to user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * R-1.6: REQUIRES_NEW deliberately, not the default REQUIRED. This method is called
     * from AuctionFinalizer/BidService/AdminAuctionService while THEY are already inside
     * their own @Transactional method — under default (REQUIRED) propagation this would
     * join that same physical transaction/Hibernate session. Its internal catch blocks
     * intentionally swallow failures (so one participant's unfreeze failure doesn't block
     * unfreezing the others or the caller's own auction-status update) — but catching an
     * exception does not undo damage already done to a shared Hibernate session: a genuine
     * DB-level failure here (e.g. ObjectOptimisticLockingFailureException from Deposit's
     * @Version check racing another concurrent mutation) can mark a shared transaction
     * rollback-only even though the exception never appears to propagate, surfacing later
     * as a confusing UnexpectedRollbackException at the OUTER commit - or silently
     * discarding unrelated work the caller did in the same transaction. REQUIRES_NEW gives
     * this method its own independent transaction: a failure here rolls back only this
     * unfreeze attempt (cleanly, atomically) and is durably recorded via
     * FailedOperationService (itself run in a separate, always-committing transaction),
     * without ever being able to poison the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unfreezeForAuction(Long userId, Long auctionId) {
        log.info("Unfreezing deposit for user: {}, auction: {}", userId, auctionId);

        try {
            Deposit deposit = depositRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

            List<FrozenDeposit> frozen = depositRepository.getFrozenDepositsByDepositId(deposit.getId());
            FrozenDeposit target = frozen.stream()
                    .filter(f -> f.getAuctionId().equals(auctionId))
                    .findFirst()
                    .orElse(null); // Не выбрасываем исключение, а просто возвращаем null

            // Если нет замороженных средств для этого аукциона - просто логируем и выходим
            // Это не должно быть ошибкой, так как аукцион мог завершиться без ставок
            if (target == null) {
                log.warn("No frozen amount found for auction {}, user {}. Skipping unfreeze.", auctionId, userId);
                return;
            }

            BigDecimal frozenAmount = target.getAmount();
            BigDecimal balanceBefore = deposit.getAvailableBalance();

            deposit.unfreezeForAuction(frozenAmount);

            depositRepository.unfreezeForAuction(deposit.getId(), auctionId);

            DepositTransaction transaction = new DepositTransaction(
                    deposit.getId(),
                    userId,
                    DepositTransaction.TransactionType.UNBLOCK,
                    frozenAmount,
                    balanceBefore,
                    deposit.getAvailableBalance(),
                    auctionId.toString(),
                    "Разблокировка средств после аукциона #" + auctionId
            );
            transaction.setAuctionId(auctionId);
            depositRepository.saveTransaction(transaction);

            depositRepository.save(deposit);
            log.info("Deposit unfrozen for auction {}. New available balance: {}", auctionId, deposit.getAvailableBalance());

            try {
                notificationService.send(userId, "DEPOSIT_UNFROZEN",
                        "Депозит разморожен",
                        String.format("Заморозка %s сом по аукциону #%d снята. Средства доступны.",
                                frozenAmount.toPlainString(), auctionId));
            } catch (Exception e2) {
                log.warn("Failed to send deposit unfreeze notification to user {}: {}", userId, e2.getMessage());
            }

        } catch (IllegalArgumentException e) {
            // Если депозит не найден - логируем и не выбрасываем исключение
            log.error("Deposit not found for user {}: {}", userId, e.getMessage());
            failedOperationService.saveFailedOperation("UNFREEZE_AUCTION", userId, auctionId, e);
        } catch (Exception e) {
            log.error("Failed to unfreeze deposit for user {} auction {}: {}", userId, auctionId, e.getMessage(), e);
            failedOperationService.saveFailedOperation("UNFREEZE_AUCTION", userId, auctionId, e);
        }
    }
    /**
     * @deprecated Используйте {@link #unfreezeForAuction(Long, Long)}
     * Оставлен для обратной совместимости с AuctionFinalizer
     */
    @Deprecated
    @Transactional
    public void unfreezeForAuction(Long userId, Long auctionId, BigDecimal startingPrice) {
        log.info("Unfreezing deposit for user: {}, auction: {} (deprecated method, startingPrice={})",
                userId, auctionId, startingPrice);
        // Игнорируем startingPrice, так как сумма хранится в БД
        unfreezeForAuction(userId, auctionId);
    }

    /**
     * Follow-up #5: REQUIRES_NEW for the same reason as unfreezeForAuction (R-1.6). This
     * method catches, records via FailedOperationService, and re-throws; its caller
     * (AuctionFinalizer.markWinnerSafely) catches that re-thrown exception and swallows it
     * WITHOUT re-throwing further, while sharing finalizeAuction's transaction under the
     * default REQUIRED propagation - the identical pattern that made unfreezeForAuction
     * capable of silently poisoning its caller's transaction. markFrozenAsWinner's bulk
     * JPQL UPDATE bypasses @Version/optimistic locking (it operates outside the entity
     * lifecycle), so an ObjectOptimisticLockingFailureException specifically cannot occur
     * here, but other DataAccessException-family failures (connection issues, lock
     * timeouts) remain possible and would carry the same risk. Isolating this in its own
     * transaction is the same fix for the same reason, not a blind copy.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsWinner(Long userId, Long auctionId) {
        log.info("Marking user: {} as winner of auction: {}", userId, auctionId);

        try {
            Deposit deposit = depositRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

            depositRepository.markFrozenAsWinner(deposit.getId(), auctionId);
            log.info("User {} marked as winner. Deposit remains frozen for auction {}", userId, auctionId);

        } catch (Exception e) {
            log.error("Failed to mark user {} as winner of auction {}: {}", userId, auctionId, e.getMessage(), e);
            failedOperationService.saveFailedOperation("MARK_WINNER", userId, auctionId, e);
            throw e;
        }
    }
    /**
     * Заморозить депозит для заявки на диагностику
     */
    @Transactional
    public void freezeForDiagnosticRequest(Long userId, Long requestId, BigDecimal amount) {
        log.info("Freezing deposit for user: {}, request: {}, amount: {}", userId, requestId, amount);

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден"));

        // Добавить проверку достаточности средств
        if (deposit.getAvailableBalance().compareTo(amount) < 0) {
            throw new IllegalStateException(
                    String.format("Недостаточно средств. Доступно: %s, требуется: %s",
                            deposit.getAvailableBalance(), amount)
            );
        }

        // Замораживаем средства
        deposit.freezeForAuction(amount);
        depositRepository.save(deposit);

        // PHASE 1 (seller commitment redesign): this method no longer persists a
        // deposit_frozen row for the diagnostic-request case - that table's auction_id
        // column is a real FK to auctions(id), and storing a diagnostic-request id there
        // was a production defect (see Phase 0 / F-0.1 audit findings). The caller
        // (SellerDiagnosticCommitmentService) now owns the correctly-typed, correctly-FK'd
        // record; this method's sole responsibility is the Deposit ledger mutation below.

        // Записываем транзакцию
        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                userId,
                DepositTransaction.TransactionType.BLOCK,
                amount,
                deposit.getAvailableBalance().add(amount),
                deposit.getAvailableBalance(),
                "DIAGNOSTIC_" + requestId,
                "Блокировка средств для заявки на диагностику #" + requestId
        );
        transaction.setAuctionId(requestId);
        depositRepository.saveTransaction(transaction);
    }

    /**
     * Разморозить депозит после завершения заявки (если отчёт не одобрен).
     *
     * PHASE 1 (seller commitment redesign): the frozen amount is now supplied by the
     * caller (SellerDiagnosticCommitmentService, which reads it from its own
     * correctly-FK'd commitment record) rather than looked up here via the
     * deposit_frozen table - that table's auction_id column is a real FK to auctions(id)
     * and was never a correct home for a diagnostic-request reference (see Phase 0 / F-0.1
     * audit findings). A missing/already-released commitment is the caller's concern to
     * detect (via a safe no-op before this method is ever invoked) - this method is only
     * ever called with a known, correct amount to release.
     */
    @Transactional
    public void unfreezeForDiagnosticRequest(Long userId, Long requestId, BigDecimal amount) {
        log.info("Unfreezing deposit for user: {}, request: {}, amount: {}", userId, requestId, amount);

        try {
            Deposit deposit = depositRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

            BigDecimal balanceBefore = deposit.getAvailableBalance();

            deposit.unfreezeForAuction(amount);

            DepositTransaction transaction = new DepositTransaction(
                    deposit.getId(),
                    userId,
                    DepositTransaction.TransactionType.UNBLOCK,
                    amount,
                    balanceBefore,
                    deposit.getAvailableBalance(),
                    "DIAGNOSTIC_" + requestId,
                    "Разблокировка средств после заявки на диагностику #" + requestId
            );
            depositRepository.saveTransaction(transaction);

            depositRepository.save(deposit);
        } catch (Exception e) {
            // R-1.2/R-1.6: в отличие от unfreezeForAuction (fail-soft, вызывается из
            // AuctionFinalizer, которое уже единолично отвечает за размораживание ВСЕХ
            // участников и не должно прерываться из-за одного), здесь исключение
            // ПЕРЕБРАСЫВАЕТСЯ: unfreezeForDiagnosticRequest — единственная операция в
            // транзакции approveReport()/rejectReport(), поэтому её сбой должен откатить
            // всю транзакцию целиком (atomic rollback), а не молча оставить отчёт
            // REJECTED/APPROVED с фактически всё ещё замороженным депозитом. Ошибка при
            // этом остаётся наблюдаемой через FailedOperationService, а не только в логах.
            log.error("Failed to unfreeze deposit for user {} request {}: {}", userId, requestId, e.getMessage(), e);
            failedOperationService.saveFailedOperation("UNFREEZE_DIAGNOSTIC_REQUEST", userId, requestId, e);
            throw e;
        }
    }

    /**
     * PHASE 3B: releases a seller diagnostic commitment's full frozen amount, permanently
     * charging {@code chargeAmount} of it (the diagnostic fee for Outcome B) and returning
     * the remainder to the seller's available balance - a single combined
     * {@link Deposit#chargeFromFrozen} mutation plus one {@code save()}, never a separate
     * unfreeze-then-withdraw pair (see that method's own Javadoc for why). Plain
     * @Transactional (REQUIRED) deliberately, NOT REQUIRES_NEW: this is called from
     * SellerDiagnosticCommitmentFinalizationService for exactly one commitment/deposit, never
     * in a fan-out loop over multiple participants - none of the REQUIRES_NEW-plus-swallow
     * reasoning that justifies it on unfreezeForAuction/markAsWinner applies here. A failure
     * here must propagate and roll back the caller's whole transaction (including the
     * commitment's own FINALIZED claim), exactly like unfreezeForDiagnosticRequest.
     *
     * balanceBefore/balanceAfter recording follows two DIFFERENT existing conventions,
     * deliberately: the CHARGE transaction records raw balance (like WITHDRAWAL/DEPOSIT/
     * ADMIN_ADJUSTMENT, since balance actually changes), while the UNBLOCK transaction
     * records availableBalance (like every existing UNBLOCK transaction in this codebase,
     * e.g. unfreezeForAuction) - using the wrong one for either would silently misrepresent
     * that transaction relative to how every other row of its type is recorded.
     */
    @Transactional
    public void chargeForDiagnosticRequest(Long depositId, Long requestId, BigDecimal totalFrozen, BigDecimal chargeAmount) {
        log.info("Charging frozen diagnostic commitment: deposit={}, request={}, totalFrozen={}, chargeAmount={}",
                depositId, requestId, totalFrozen, chargeAmount);

        Deposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден: " + depositId));

        Long userId = deposit.getUserId();
        BigDecimal rawBalanceBefore = deposit.getBalance();
        BigDecimal availableBefore = deposit.getAvailableBalance();

        deposit.chargeFromFrozen(totalFrozen, chargeAmount);
        depositRepository.save(deposit);

        BigDecimal rawBalanceAfter = deposit.getBalance();
        BigDecimal availableAfter = deposit.getAvailableBalance();

        if (chargeAmount.compareTo(BigDecimal.ZERO) > 0) {
            DepositTransaction charge = new DepositTransaction(
                    deposit.getId(), userId,
                    DepositTransaction.TransactionType.CHARGE,
                    chargeAmount, rawBalanceBefore, rawBalanceAfter,
                    "DIAGNOSTIC_" + requestId,
                    "Удержание диагностической комиссии по заявке #" + requestId
            );
            charge.setAuctionId(requestId);
            depositRepository.saveTransaction(charge);
        }

        BigDecimal refund = totalFrozen.subtract(chargeAmount);
        if (refund.compareTo(BigDecimal.ZERO) > 0) {
            DepositTransaction unblock = new DepositTransaction(
                    deposit.getId(), userId,
                    DepositTransaction.TransactionType.UNBLOCK,
                    refund, availableBefore, availableAfter,
                    "DIAGNOSTIC_" + requestId,
                    "Возврат остатка заморозки по заявке #" + requestId
            );
            unblock.setAuctionId(requestId);
            depositRepository.saveTransaction(unblock);
        }

        log.info("Charge complete for request {}: balance={}, blockedAmount={}",
                requestId, deposit.getBalance(), deposit.getBlockedAmount());
    }

    /**
     * @param requiredAmount фиксированная сумма депозита, требуемая для конкретного аукциона
     *                       (auction.getRequiredDepositAmount()) — НЕ стартовая цена и не процент от неё.
     */
    @Transactional(readOnly = true)
    public boolean canParticipateInAuction(Long userId, BigDecimal requiredAmount) {
        if (requiredAmount == null || requiredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        return depositRepository.findByUserId(userId)
                .map(deposit -> deposit.getAvailableBalance().compareTo(requiredAmount) >= 0)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean canParticipateInAuction(Long userId) {
        return depositRepository.findByUserId(userId)
                .map(deposit -> deposit.getAvailableBalance().compareTo(BigDecimal.ZERO) > 0)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public BigDecimal getFrozenAmountForAuction(Long userId, Long auctionId) {
        return depositRepository.findByUserId(userId)
                .map(d -> depositRepository.getFrozenDepositsByDepositId(d.getId()).stream()
                        .filter(f -> f.getAuctionId().equals(auctionId))
                        .map(FrozenDeposit::getAmount)
                        .findFirst()
                        .orElse(BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public DepositResponse getDepositByUserId(Long userId) {
        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        return DepositResponse.fromDomain(deposit);
    }

    @Transactional(readOnly = true)
    public List<DepositTransactionResponse> getTransactionsByUserId(Long userId) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .map(DepositTransactionResponse::fromDomain)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DepositTransactionResponse> getTransactionsFiltered(
            Long userId,
            DepositTransaction.TransactionType type,
            java.time.OffsetDateTime from,
            java.time.OffsetDateTime to) {
        return depositRepository.findTransactionsByUserIdFiltered(userId, type, from, to).stream()
                .map(DepositTransactionResponse::fromDomain)
                .collect(Collectors.toList());
    }

    /**
     * R-2: used by FinancialRecoveryService to check whether an UNFREEZE_AUCTION /
     * UNFREEZE_DIAGNOSTIC_REQUEST retry is still needed, and again afterward to verify it
     * actually took effect. REQUIRES_NEW deliberately: the caller (FinancialRecoveryService
     * .recoverOne) is itself @Transactional, and the retry it performs
     * (unfreezeForAuction) runs in ITS OWN REQUIRES_NEW transaction/session - without
     * REQUIRES_NEW here too, this read would share the caller's persistence context and
     * Hibernate's first-level cache would return the entity state as it was BEFORE the
     * retry (a stale, already-loaded instance), not the freshly-committed post-retry
     * state, making "verify resulting state" silently useless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isStillFrozenForAuction(Long userId, Long auctionId) {
        Long depositId = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Депозит не найден для пользователя: " + userId))
                .getId();
        return depositRepository.isFrozenForAuction(depositId, auctionId);
    }

    /**
     * R-2: same REQUIRES_NEW reasoning as {@link #isStillFrozenForAuction}, but ALSO
     * deliberately uses DepositRepository.needsWinnerMark (a boolean-returning
     * exists-style query) rather than getFrozenDepositsByDepositId(...).stream()...,
     * which hydrates a managed JpaDepositFrozenEntity and was confirmed - by direct
     * reproduction - to return a stale, already-loaded instance instead of the freshly
     * committed post-retry state even under REQUIRES_NEW. A boolean/exists query never
     * touches Hibernate's identity map and was confirmed unaffected.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean needsWinnerMark(Long userId, Long auctionId) {
        Long depositId = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Депозит не найден для пользователя: " + userId))
                .getId();
        return depositRepository.needsWinnerMark(depositId, auctionId);
    }

    @Transactional
    public void releaseWinnerDeposit(Long userId, Long auctionId) {
        log.info("Releasing winner deposit for user: {}, auction: {}", userId, auctionId);

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        List<FrozenDeposit> frozen = depositRepository.getFrozenDepositsByDepositId(deposit.getId());
        FrozenDeposit target = frozen.stream()
                .filter(f -> f.getAuctionId().equals(auctionId) && f.isWinner())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Заморозка победителя для аукциона " + auctionId + " не найдена. " +
                        "Убедитесь, что аукцион завершён и пользователь является победителем."));

        BigDecimal frozenAmount = target.getAmount();
        BigDecimal balanceBefore = deposit.getAvailableBalance();

        deposit.unfreezeForAuction(frozenAmount);
        depositRepository.unfreezeForAuction(deposit.getId(), auctionId);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                userId,
                DepositTransaction.TransactionType.UNBLOCK,
                frozenAmount,
                balanceBefore,
                deposit.getAvailableBalance(),
                auctionId.toString(),
                "Разблокировка депозита победителя аукциона #" + auctionId + " (подтверждено администратором)"
        );
        transaction.setAuctionId(auctionId);
        depositRepository.saveTransaction(transaction);

        depositRepository.save(deposit);
        log.info("Winner deposit released for user {}, auction {}. Amount: {}", userId, auctionId, frozenAmount);

        try {
            notificationService.send(userId, "DEPOSIT_RELEASED",
                    "Депозит освобождён",
                    String.format("Заморозка %s сом по аукциону #%d снята администратором.",
                            frozenAmount.toPlainString(), auctionId));
        } catch (Exception e) {
            log.warn("Failed to send deposit release notification to user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * X3-A (SALE_FAILED outcomes B, PLATFORM_FAULT, C): unblocks the winner's frozen deposit
     * for an auction that failed without requiring confiscation - the money returns to the
     * buyer's own available balance. Deliberately auction-aware AND winner-aware (the frozen
     * row must belong to exactly this auction AND be winner-flagged) - same precondition
     * releaseWinnerDeposit already uses, but this is NOT a reuse of that method: this one is
     * called only from SaleFailureResolutionOrchestrator.resolveSaleFailure(), plain
     * @Transactional (REQUIRED), deliberately NOT REQUIRES_NEW - a failure here must roll back
     * the whole outcome resolution (Auction, SaleResolution, Vehicle included), not be isolated
     * or swallowed the way unfreezeForAuction/markAsWinner's fan-out-over-many-participants
     * REQUIRES_NEW is.
     */
    @Transactional
    public void unblockForSaleFailure(Long auctionId, Long winnerId) {
        log.info("Unblocking winner deposit after SALE_FAILED: auction={}, winner={}", auctionId, winnerId);

        Deposit deposit = depositRepository.findByUserId(winnerId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + winnerId));

        FrozenDeposit target = findWinnerFrozenDeposit(deposit, auctionId);

        BigDecimal frozenAmount = target.getAmount();
        BigDecimal balanceBefore = deposit.getAvailableBalance();

        deposit.unfreezeForAuction(frozenAmount);
        depositRepository.unfreezeForAuction(deposit.getId(), auctionId);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                winnerId,
                DepositTransaction.TransactionType.UNBLOCK,
                frozenAmount,
                balanceBefore,
                deposit.getAvailableBalance(),
                auctionId.toString(),
                "Разблокировка депозита победителя после неудачной продажи аукциона #" + auctionId
        );
        transaction.setAuctionId(auctionId);
        depositRepository.saveTransaction(transaction);

        depositRepository.save(deposit);
        log.info("Winner deposit unblocked after SALE_FAILED for auction {}, winner {}. Amount: {}",
                auctionId, winnerId, frozenAmount);
    }

    /**
     * X3-A (SALE_FAILED outcome BUYER_FAULT): confiscates the winner's frozen deposit to the
     * platform - unlike unblockForSaleFailure, the money does NOT return to the buyer's
     * available balance; it is permanently removed from both blockedAmount and balance via
     * {@link Deposit#confiscateFrozen}. Deliberately NOT implemented via withdraw() (which
     * checks availableBalance - the wrong gate, since this amount is specifically the frozen
     * portion, not the available portion) and does NOT introduce a new ledger transaction type
     * - reuses the existing, previously-unused CHARGE type. Same transaction-propagation
     * reasoning as unblockForSaleFailure above - plain @Transactional (REQUIRED), no
     * REQUIRES_NEW.
     */
    @Transactional
    public void confiscateToPlatform(Long auctionId, Long winnerId) {
        log.info("Confiscating winner deposit to platform after BUYER_FAULT: auction={}, winner={}", auctionId, winnerId);

        Deposit deposit = depositRepository.findByUserId(winnerId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + winnerId));

        FrozenDeposit target = findWinnerFrozenDeposit(deposit, auctionId);

        BigDecimal frozenAmount = target.getAmount();
        BigDecimal balanceBefore = deposit.getBalance();

        deposit.confiscateFrozen(frozenAmount);
        depositRepository.unfreezeForAuction(deposit.getId(), auctionId);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                winnerId,
                DepositTransaction.TransactionType.CHARGE,
                frozenAmount,
                balanceBefore,
                deposit.getBalance(),
                auctionId.toString(),
                "Конфискация депозита победителя в пользу платформы (BUYER_FAULT), аукцион #" + auctionId
        );
        transaction.setAuctionId(auctionId);
        depositRepository.saveTransaction(transaction);

        depositRepository.save(deposit);
        log.info("Winner deposit confiscated to platform for auction {}, winner {}. Amount: {}",
                auctionId, winnerId, frozenAmount);
    }

    /**
     * Shared winner-frozen-deposit lookup for unblockForSaleFailure/confiscateToPlatform -
     * same precondition releaseWinnerDeposit uses inline, extracted here only because these
     * two new X3-A methods both need it (not a change to releaseWinnerDeposit itself).
     */
    private FrozenDeposit findWinnerFrozenDeposit(Deposit deposit, Long auctionId) {
        return depositRepository.getFrozenDepositsByDepositId(deposit.getId()).stream()
                .filter(f -> f.getAuctionId().equals(auctionId) && f.isWinner())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Заморозка победителя для аукциона " + auctionId + " не найдена. " +
                        "Убедитесь, что аукцион завершён и пользователь является победителем."));
    }

    static final BigDecimal MAX_ADJUST_AMOUNT = new BigDecimal("99999999");

    @Transactional
    public DepositResponse adminAdjust(Long targetUserId, BigDecimal amount, String comment, String idempotencyKey) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("Комментарий к корректировке обязателен");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Сумма корректировки не может быть null");
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Сумма корректировки не может быть нулевой");
        }
        if (amount.abs().compareTo(MAX_ADJUST_AMOUNT) > 0) {
            throw new IllegalArgumentException(
                    "Сумма корректировки не может превышать " + MAX_ADJUST_AMOUNT);
        }
        // R-1.4: ключ идемпотентности обязателен для всех финансовых admin-корректировок.
        // Клиент обязан сгенерировать его ОДИН раз для данной попытки корректировки и
        // переиспользовать его при повторной отправке того же запроса (например, после
        // сетевой ошибки) — иначе повторный запрос с тем же ключом гарантированно
        // возвращает уже применённый результат, не выполняя повторное зачисление/списание.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Ключ идемпотентности обязателен для административной корректировки депозита");
        }

        Optional<DepositTransaction> existing = depositRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            DepositTransaction prev = existing.get();
            // Follow-up item 2: a key match alone is not enough - verify the replay is for
            // the SAME operation (same target user, same signed amount, same type), not just
            // a key collision with a different mutation silently returning stale state.
            boolean sameFingerprint = prev.getUserId().equals(targetUserId)
                    && prev.getType() == DepositTransaction.TransactionType.ADMIN_ADJUSTMENT
                    && prev.getAmount().compareTo(amount) == 0;
            if (!sameFingerprint) {
                throw new IllegalStateException(
                        "Ключ идемпотентности '" + idempotencyKey + "' уже использован для другой операции " +
                        "(другая сумма, пользователь или тип операции)");
            }
            log.info("Idempotent replay of admin adjustment for key '{}', target user {}. No new mutation applied.",
                    idempotencyKey, targetUserId);
            return getDepositByUserId(targetUserId);
        }

        Deposit deposit = depositRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + targetUserId));

        BigDecimal balanceBefore = deposit.getBalance();

        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            deposit.deposit(amount);
        } else {
            // Отрицательная корректировка — списание. withdraw проверяет availableBalance.
            deposit.withdraw(amount.negate());
        }

        DepositTransaction tx = new DepositTransaction(
                deposit.getId(), targetUserId,
                DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                amount, balanceBefore, deposit.getBalance(),
                "ADMIN_ADJUSTMENT", comment
        );
        tx.setIdempotencyKey(idempotencyKey);
        // Уникальность idempotency_key обеспечена на уровне БД (частичный уникальный индекс);
        // конкурентный дубликат с тем же ключом завершится DataIntegrityViolationException
        // и полным откатом транзакции — повторного зачисления не произойдёт.
        depositRepository.saveTransaction(tx);
        Deposit saved = depositRepository.save(deposit);
        log.info("Admin adjusted deposit for user {}: amount={}, comment={}, idempotencyKey={}",
                targetUserId, amount, comment, idempotencyKey);
        return DepositResponse.fromDomain(saved);
    }
}