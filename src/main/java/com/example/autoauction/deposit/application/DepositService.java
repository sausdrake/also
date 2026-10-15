// deposit/application/DepositService.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.common.application.FailedOperationService;
import com.example.autoauction.deposit.domain.AdminAdjustmentReason;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.FreezeReason;
import com.example.autoauction.deposit.domain.FrozenDeposit;
import com.example.autoauction.deposit.domain.LedgerIdempotencyConflictException;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.deposit.web.dto.DepositTransactionResponse;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final DepositUnfreezeTransactionService depositUnfreezeTransactionService;
    private final ApplicationEventPublisher eventPublisher;

    public DepositService(DepositRepository depositRepository,
                          FailedOperationService failedOperationService,
                          NotificationService notificationService,
                          DepositUnfreezeTransactionService depositUnfreezeTransactionService,
                          ApplicationEventPublisher eventPublisher) {
        this.depositRepository = depositRepository;
        this.failedOperationService = failedOperationService;
        this.notificationService = notificationService;
        this.depositUnfreezeTransactionService = depositUnfreezeTransactionService;
        this.eventPublisher = eventPublisher;
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

    /** F6: ledger idempotency-key prefix of a rejected withdrawal request's return. */
    public static final String WITHDRAWAL_RETURN_KEY_PREFIX = "WITHDRAWAL_RETURN:";

    /** F6: ledger reference shared by every row of one withdrawal request. */
    public static String withdrawalReference(Long withdrawalRequestId) {
        return "WITHDRAWAL_REQUEST_" + withdrawalRequestId;
    }

    /**
     * R-1.3: idempotencyKey обязателен. Клиент генерирует его один раз на попытку вывода
     * и переиспользует при повторной отправке того же запроса (например, после сетевой
     * ошибки/потери ответа) — повтор с тем же ключом возвращает уже применённый результат,
     * не выполняя повторное списание.
     *
     * <p>F6: this is the debit primitive of a withdrawal request - the only production caller is
     * {@code WithdrawalRequestService.create}, which records the request in the same transaction.
     * It is never exposed on its own: there is no automatic payout.
     */
    @Transactional
    public DepositResponse withdraw(Long userId, BigDecimal amount, String idempotencyKey) {
        return withdraw(userId, amount, idempotencyKey, "WITHDRAWAL", "Вывод средств с депозита");
    }

    /** F6: {@link #withdraw(Long, BigDecimal, String)} with the request's own ledger reference. */
    @Transactional
    public DepositResponse withdraw(Long userId, BigDecimal amount, String idempotencyKey,
                                    String reference, String description) {
        log.info("Withdraw for user: {}, amount: {}", userId, amount);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Ключ идемпотентности обязателен для вывода средств");
        }
        requireTyiynPrecision(amount);

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
                reference,
                description
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

        // Deferred to AFTER_COMMIT (see DepositFrozenEventListener): a rolled-back/retried freeze
        // must never publish a duplicate/phantom notification, and a post-commit notification
        // failure must never invalidate an already-committed freeze (G1).
        eventPublisher.publishEvent(new DepositFrozenEvent(userId, auctionId, requiredAmount));
    }

    /**
     * R-1.6: the mutation itself must run in its own independent transaction, isolated from
     * whatever transaction the caller (AuctionFinalizer/BidService/AdminAuctionService) is already
     * inside. D4 defect fix (PLAN/VERIFY #3, Option B): that isolated mutation now lives entirely
     * in {@link DepositUnfreezeTransactionService#unfreeze}, a genuinely separate bean - calling it
     * from here is a plain Java call through Spring's normal (proxied) dependency reference, not a
     * same-bean {@code this.method()} call. This method itself deliberately carries NO
     * {@code @Transactional} of its own: if the mutation's REQUIRES_NEW transaction fails to commit
     * (e.g. a genuine {@code @Version} optimistic-lock conflict, detected via
     * {@code saveAndFlush}), whatever exception surfaces - including a possible
     * {@code UnexpectedRollbackException} from that OTHER bean's own proxy - is thrown
     * synchronously as part of THIS single call, fully caught below, and durably recorded via
     * FailedOperationService (itself run in a separate, always-committing transaction) - without
     * ever having had a transaction of its own that Spring's proxy could fail to commit.
     */
    public void unfreezeForAuction(Long userId, Long auctionId) {
        log.info("Unfreezing deposit for user: {}, auction: {}", userId, auctionId);

        try {
            Optional<BigDecimal> unfrozenAmount = depositUnfreezeTransactionService.unfreeze(userId, auctionId);

            if (unfrozenAmount.isPresent()) {
                BigDecimal frozenAmount = unfrozenAmount.get();
                try {
                    notificationService.send(userId, "DEPOSIT_UNFROZEN",
                            "Депозит разморожен",
                            String.format("Заморозка %s сом по аукциону #%d снята. Средства доступны.",
                                    frozenAmount.toPlainString(), auctionId));
                } catch (Exception e2) {
                    log.warn("Failed to send deposit unfreeze notification to user {}: {}", userId, e2.getMessage());
                }
            }
            // Если ничего не было заморожено - DepositUnfreezeTransactionService уже залогировал
            // это как no-op; здесь дополнительных действий не требуется.

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

            depositRepository.saveAndFlush(deposit);
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
     * X3-E: charges a seller's frozen diagnostic commitment for an Outcome-C (seller
     * violation) penalty and refunds any remainder - structurally identical to
     * {@link #chargeForDiagnosticRequest} (same {@link Deposit#chargeFromFrozen} primitive,
     * same CHARGE-then-UNBLOCK transaction pair, same balanceBefore/balanceAfter recording
     * convention) but with its OWN, accurate descriptions. Deliberately NOT a call to
     * chargeForDiagnosticRequest itself - that method's description text ("Удержание
     * диагностической комиссии...", "diagnostic commission withholding") would be factually
     * wrong for a seller-violation penalty charge, misrepresenting it in an immutable
     * financial audit trail (see the X3-E final scope report, Phase 2 #1 / section 9 -
     * "do NOT reuse chargeForDiagnosticRequest() verbatim").
     *
     * X3-E corrective: {@code auctionId} is an explicit, separate parameter - NEVER the
     * diagnosticRequestId. {@code DepositTransaction.auctionId} is a genuine Auction identity
     * field (see {@code confiscateToPlatform}/{@code unblockForSaleFailure}, which always pass
     * a real auction id there) and must never be overloaded with a different domain identity.
     * The two triggers that call this method resolve it differently: the post-auction
     * SALE_FAILED_C trigger passes the real {@code Auction.getId()}; the pre-auction
     * DIAGNOSTIC_REJECTION trigger passes {@code null} - no Auction exists at that stage and
     * none is fabricated. The diagnostic-request identity remains represented exactly where
     * the existing ledger model already represents it for this kind of charge - the
     * {@code reference} string ("DIAGNOSTIC_" + diagnosticRequestId, matching
     * chargeForDiagnosticRequest's own convention) - never conflated with auctionId.
     *
     * No platform wallet/treasury credit is created here or anywhere else (locked X3-E MVP
     * platform-accounting decision) - this only ever reduces the seller's own frozen
     * collateral, exactly mirroring how confiscateToPlatform only ever reduces the buyer's own
     * balance.
     */
    /**
     * MVP-005: the seller pays for a re-diagnostic caused by his own violation. Charged once,
     * when the re-diagnostic is ordered, straight from the available balance under the deposit's
     * row lock - so it is owed regardless of how the new diagnostic cycle ends (the new
     * request's commitment carries a zero fee, so it can never be charged a second time).
     * Fails with "Недостаточно доступных средств" when the balance cannot cover it.
     */
    @Transactional
    public void chargeRediagnosticFee(Long userId, Long requestId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Deposit deposit = depositRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден"));
        BigDecimal balanceBefore = deposit.getBalance();
        deposit.withdraw(amount);
        depositRepository.save(deposit);

        DepositTransaction charge = new DepositTransaction(
                deposit.getId(), userId,
                DepositTransaction.TransactionType.CHARGE,
                amount, balanceBefore, deposit.getBalance(),
                "REDIAGNOSTIC_" + requestId,
                "Оплата повторной диагностики по вине продавца, заявка #" + requestId
        );
        charge.setAuctionId(requestId);
        depositRepository.saveTransaction(charge);
        log.info("Re-diagnostic fee {} charged to user {} for request {}", amount, userId, requestId);
    }

    @Transactional
    public void chargeSellerViolationPenalty(Long depositId, Long diagnosticRequestId, Long auctionId,
                                              BigDecimal totalFrozen, BigDecimal chargeAmount) {
        log.info("Charging seller violation penalty: deposit={}, request={}, auction={}, totalFrozen={}, chargeAmount={}",
                depositId, diagnosticRequestId, auctionId, totalFrozen, chargeAmount);

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
                    "DIAGNOSTIC_" + diagnosticRequestId,
                    "Удержание штрафа за нарушение продавцом условий сделки по заявке #" + diagnosticRequestId
            );
            charge.setAuctionId(auctionId);
            depositRepository.saveTransaction(charge);
        }

        BigDecimal refund = totalFrozen.subtract(chargeAmount);
        if (refund.compareTo(BigDecimal.ZERO) > 0) {
            DepositTransaction unblock = new DepositTransaction(
                    deposit.getId(), userId,
                    DepositTransaction.TransactionType.UNBLOCK,
                    refund, availableBefore, availableAfter,
                    "DIAGNOSTIC_" + diagnosticRequestId,
                    "Возврат остатка после применения штрафа за нарушение по заявке #" + diagnosticRequestId
            );
            unblock.setAuctionId(auctionId);
            depositRepository.saveTransaction(unblock);
        }

        log.info("Seller violation penalty charge complete for request {}: balance={}, blockedAmount={}",
                diagnosticRequestId, deposit.getBalance(), deposit.getBlockedAmount());
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

        // Deferred to AFTER_COMMIT (see DepositReleasedEventListener): a rolled-back completeSale
        // (e.g. a later settlement/commitment-resolution failure in the same transaction) must
        // never publish a duplicate/phantom notification, and a post-commit notification failure
        // must never invalidate an already-committed release (G2).
        eventPublisher.publishEvent(new DepositReleasedEvent(userId, auctionId, frozenAmount));
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

    /**
     * F6: credits a rejected withdrawal request's amount back to the deposit. The ledger key
     * {@code WITHDRAWAL_RETURN:<requestId>} is unique in the database, so a second return for the
     * same request is impossible even if a caller's own status guard were bypassed. Runs inside
     * the caller's transaction: if the return fails, the rejection rolls back with it.
     */
    @Transactional
    public void returnWithdrawal(Long userId, Long withdrawalRequestId, BigDecimal amount) {
        Deposit deposit = depositRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));
        String key = WITHDRAWAL_RETURN_KEY_PREFIX + withdrawalRequestId;
        if (depositRepository.findTransactionByIdempotencyKey(key).isPresent()) {
            throw new IllegalStateException("Сумма по заявке на вывод #" + withdrawalRequestId + " уже возвращена");
        }
        BigDecimal balanceBefore = deposit.getBalance();
        deposit.deposit(amount);

        DepositTransaction tx = new DepositTransaction(
                deposit.getId(), userId,
                DepositTransaction.TransactionType.WITHDRAWAL_RETURN,
                amount, balanceBefore, deposit.getBalance(),
                withdrawalReference(withdrawalRequestId),
                "Возврат: заявка на вывод #" + withdrawalRequestId + " отклонена"
        );
        tx.setIdempotencyKey(key);
        depositRepository.saveTransaction(tx);
        depositRepository.save(deposit);
        log.info("Withdrawal request {} amount {} returned to user {}", withdrawalRequestId, amount, userId);
    }

    /**
     * S11: every money column is NUMERIC(19,2). A client-supplied amount with more fractional
     * digits would be rounded silently by the database, so the stored ledger amount would no
     * longer equal the balance delta and an idempotent replay would compare against the rounded
     * value. Trailing zeros (100.100) are fine - only the value's real precision counts.
     */
    private static void requireTyiynPrecision(BigDecimal amount) {
        if (amount != null && amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Сумма не может содержать больше двух знаков после запятой");
        }
    }

    /** Slice 2 · Step 2A hardening: replaces the previous, un-hardened {@code 99_999_999} cap. */
    static final BigDecimal MAX_ADJUST_AMOUNT = new BigDecimal("500000");

    /**
     * Slice 2 · Step 2A: the single canonical administrative deposit-correction operation.
     * Supports both credit (positive) and debit (negative) adjustments through this one method -
     * deliberately not split into separate endpoints/primitives (CHARGEBACK/SUPPORT_CORRECTION
     * inherently need debit capability; splitting would reopen the multi-endpoint problem this
     * hardening exists to close).
     *
     * @param actingAdminId the authenticated admin performing this adjustment - NEVER supplied by
     *                       the client; the caller (the canonical controller) must derive it from
     *                       the security context. Required non-null for every new row this method
     *                       creates (the {@code performed_by_admin_id} column itself stays
     *                       nullable for historical-row compatibility, per V42's own design).
     */
    @Transactional
    public DepositResponse adminAdjust(Long targetUserId, BigDecimal amount, AdminAdjustmentReason reason,
                                       String reference, String idempotencyKey, Long actingAdminId) {
        if (reason == null) {
            throw new IllegalArgumentException("Причина корректировки обязательна");
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Ссылка (reference) обязательна для корректировки депозита");
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
        requireTyiynPrecision(amount);
        // R-1.4: ключ идемпотентности обязателен для всех финансовых admin-корректировок.
        // Клиент обязан сгенерировать его ОДИН раз для данной попытки корректировки и
        // переиспользовать его при повторной отправке того же запроса (например, после
        // сетевой ошибки) — иначе повторный запрос с тем же ключом гарантированно
        // возвращает уже применённый результат, не выполняя повторное зачисление/списание.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Ключ идемпотентности обязателен для административной корректировки депозита");
        }
        if (actingAdminId == null) {
            throw new IllegalArgumentException("Не удалось определить администратора, выполняющего корректировку");
        }

        // Slice 2 Phase 2 lock (§11/§15/§27): a RECONCILIATION adjustment must never duplicate an
        // automatic payment credit already recorded for the payment this adjustment references.
        // Treated as a fully opaque idempotency-key probe - this module never parses, validates,
        // or interprets the "PAYMENT:" convention; it only asks "does a ledger row already exist
        // for this exact key", using the SAME existing port method adminAdjust's own idempotency
        // check already uses. No payment.* dependency is introduced by this check.
        if (reason == AdminAdjustmentReason.RECONCILIATION
                && depositRepository.findTransactionByIdempotencyKey(reference).isPresent()) {
            throw new IllegalStateException(
                    "Корректировка отклонена: по ссылке '" + reference + "' уже существует зачисление " +
                    "в депозитном реестре — повторное зачисление приведёт к задвоению средств");
        }

        Optional<DepositTransaction> existing = depositRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            DepositTransaction prev = existing.get();
            // Follow-up item 2 (pre-existing) + Step 2A: a key match alone is not enough - verify
            // the replay is for the SAME operation (same target user, same signed amount, same
            // type, same reason, same reference), not just a key collision with a different
            // mutation silently returning stale state.
            boolean sameFingerprint = prev.getUserId().equals(targetUserId)
                    && prev.getType() == DepositTransaction.TransactionType.ADMIN_ADJUSTMENT
                    && prev.getAmount().compareTo(amount) == 0
                    && reason.name().equals(prev.getDescription())
                    && reference.equals(prev.getReference());
            if (!sameFingerprint) {
                throw new IllegalStateException(
                        "Ключ идемпотентности '" + idempotencyKey + "' уже использован для другой операции " +
                        "(другая сумма, пользователь, причина, ссылка или тип операции)");
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
            // Отрицательная корректировка — списание. withdraw проверяет availableBalance, то есть
            // не допускает ни отрицательный доступный баланс, ни списание замороженного залога.
            deposit.withdraw(amount.negate());
        }

        DepositTransaction tx = new DepositTransaction(
                deposit.getId(), targetUserId,
                DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                amount, balanceBefore, deposit.getBalance(),
                reference, reason.name()
        );
        tx.setIdempotencyKey(idempotencyKey);
        tx.setPerformedByAdminId(actingAdminId);
        // Уникальность idempotency_key обеспечена на уровне БД (частичный уникальный индекс);
        // конкурентный дубликат с тем же ключом завершится DataIntegrityViolationException
        // и полным откатом транзакции — повторного зачисления не произойдёт.
        depositRepository.saveTransaction(tx);
        Deposit saved = depositRepository.save(deposit);
        log.info("Admin adjusted deposit for user {}: amount={}, reason={}, reference={}, actingAdminId={}, idempotencyKey={}",
                targetUserId, amount, reason, reference, actingAdminId, idempotencyKey);
        return DepositResponse.fromDomain(saved);
    }

    /**
     * Slice 2 · Step 2A: the narrow deposit-side primitive the future payment application layer
     * (Step 2D) will use to credit a deposit once a {@code Payment} is confirmed - WITHOUT this
     * method or this class ever depending on anything from {@code payment.*}. Plain
     * {@code @Transactional} (REQUIRED, the default) deliberately - this must participate in the
     * CALLER's own physical transaction (the future canonical confirmation operation), never open
     * an independent one, so that the ledger write and the caller's own Payment-status/event writes
     * commit or roll back together. Never populates {@code performedByAdminId} (there is no human
     * admin actor for an automatic credit) and never uses {@code TransactionType.ADMIN_ADJUSTMENT}.
     *
     * <p>Slice D3: acquires the Deposit row's pessimistic lock ({@code findByUserIdForUpdate})
     * FIRST, unconditionally - before the ledger-idempotency check - mirroring
     * {@code PaymentCreationService.createPayment}'s own established lock-then-check order.
     * Serializes this operation against every other Deposit-mutating path for the same user
     * (e.g. two different Payments for the same user confirmed concurrently), closing the race
     * the unlocked {@code findByUserId} read previously left open.
     */
    @Transactional
    public DepositResponse creditFromPayment(DepositCreditCommand command) {
        Deposit deposit = depositRepository.findByUserIdForUpdate(command.userId())
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + command.userId()));

        Optional<DepositTransaction> existing =
                depositRepository.findTransactionByIdempotencyKey(command.ledgerIdempotencyKey());
        if (existing.isPresent()) {
            DepositTransaction prev = existing.get();
            // The idempotency key is globally unique, so a collision is NOT by itself proof of a
            // safe replay - it could be a different operation that happens to share the key
            // (client/provider bug, key-reuse, or a row belonging to someone else entirely). Every
            // field of the operation's fingerprint must match exactly before this is treated as a
            // no-op; any mismatch is a conflict, never a silent "return someone else's deposit".
            boolean sameFingerprint = prev.getUserId().equals(command.userId())
                    && prev.getType() == DepositTransaction.TransactionType.PAYMENT_CREDIT
                    && prev.getAmount().compareTo(command.amount()) == 0
                    && command.ledgerReference().equals(prev.getReference())
                    && command.ledgerIdempotencyKey().equals(prev.getIdempotencyKey());
            if (!sameFingerprint) {
                // R1: a distinct, catchable type (not the generic IllegalStateException this
                // module otherwise uses) specifically so a payment.* caller can recognize and
                // react to exactly this cross-payment-identity conflict without swallowing every
                // other IllegalStateException this method - and this class - can throw.
                throw new LedgerIdempotencyConflictException(command.ledgerIdempotencyKey(),
                        "Ключ идемпотентности '" + command.ledgerIdempotencyKey() + "' уже использован для другой операции " +
                        "(другой пользователь, сумма, ссылка или тип операции) - автоматическое зачисление отклонено");
            }
            log.info("Idempotent replay of automatic payment credit for key '{}', user {}. No new mutation applied.",
                    command.ledgerIdempotencyKey(), command.userId());
            return getDepositByUserId(command.userId());
        }

        BigDecimal balanceBefore = deposit.getBalance();
        deposit.deposit(command.amount());

        DepositTransaction tx = new DepositTransaction(
                deposit.getId(), command.userId(),
                DepositTransaction.TransactionType.PAYMENT_CREDIT,
                command.amount(), balanceBefore, deposit.getBalance(),
                command.ledgerReference(), "Автоматическое зачисление по платежу"
        );
        tx.setIdempotencyKey(command.ledgerIdempotencyKey());
        // performedByAdminId intentionally left null - no human admin actor for an automatic credit.
        depositRepository.saveTransaction(tx);
        Deposit saved = depositRepository.save(deposit);
        log.info("Automatic payment credit applied for user {}: amount={}, reference={}, idempotencyKey={}",
                command.userId(), command.amount(), command.ledgerReference(), command.ledgerIdempotencyKey());
        return DepositResponse.fromDomain(saved);
    }
}