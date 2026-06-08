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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
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

    @Transactional
    public DepositResponse deposit(Long userId, BigDecimal amount) {
        log.info("Deposit for user: {}, amount: {}", userId, amount);

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        BigDecimal balanceBefore = deposit.getBalance();
        deposit.deposit(amount);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                userId,
                DepositTransaction.TransactionType.DEPOSIT,
                amount,
                balanceBefore,
                deposit.getBalance(),
                "DEPOSIT",
                "Пополнение депозита"
        );
        depositRepository.saveTransaction(transaction);

        Deposit saved = depositRepository.save(deposit);
        log.info("Deposit successful. New balance: {}", saved.getBalance());

        try {
            notificationService.send(userId, "DEPOSIT_ADDED",
                    "Депозит пополнен",
                    String.format("Ваш депозит успешно пополнен на %s сом. Текущий баланс: %s сом.",
                            amount.toPlainString(), saved.getBalance().toPlainString()));
        } catch (Exception e) {
            log.warn("Failed to send deposit added notification to user {}: {}", userId, e.getMessage());
        }

        return DepositResponse.fromDomain(saved);
    }

    @Transactional
    public DepositResponse withdraw(Long userId, BigDecimal amount) {
        log.info("Withdraw for user: {}, amount: {}", userId, amount);

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
        depositRepository.saveTransaction(transaction);

        Deposit saved = depositRepository.save(deposit);
        log.info("Withdraw successful. New balance: {}", saved.getBalance());
        return DepositResponse.fromDomain(saved);
    }

    @Transactional
    public void freezeForAuction(Long userId, Long auctionId, BigDecimal startingPrice) {
        log.info("Freezing deposit for user: {}, auction: {}", userId, auctionId);

        if (startingPrice == null || startingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid startingPrice: {}. Cannot freeze deposit.", startingPrice);
            throw new IllegalArgumentException("Starting price must be positive");
        }

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        BigDecimal requiredAmount = Deposit.calculateRequiredDeposit(startingPrice);

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
                    String.format("Для участия в аукционе #%d заморожено %s сом (1%% от стартовой цены).",
                            auctionId, requiredAmount.toPlainString()));
        } catch (Exception e) {
            log.warn("Failed to send deposit freeze notification to user {}: {}", userId, e.getMessage());
        }
    }

    @Transactional
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
        } catch (Exception e) {
            log.error("Failed to unfreeze deposit for user {} auction {}: {}", userId, auctionId, e.getMessage(), e);
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

    @Transactional
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

        // Сохраняем информацию о заморозке (используем auctionId как requestId)
        depositRepository.freezeForAuction(deposit.getId(), requestId, amount, FreezeReason.DIAGNOSTIC_REQUEST);

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
     * Разморозить депозит после завершения заявки (если отчёт не одобрен)
     */
    @Transactional
    public void unfreezeForDiagnosticRequest(Long userId, Long requestId) {
        log.info("Unfreezing deposit for user: {}, request: {}", userId, requestId);

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        List<FrozenDeposit> frozen = depositRepository.getFrozenDepositsByDepositId(deposit.getId());
        FrozenDeposit target = frozen.stream()
                .filter(f -> f.getAuctionId().equals(requestId))
                .findFirst()
                .orElse(null);

        if (target == null) {
            log.warn("No frozen amount found for request {}, user {}", requestId, userId);
            return;
        }

        BigDecimal frozenAmount = target.getAmount();
        BigDecimal balanceBefore = deposit.getAvailableBalance();

        deposit.unfreezeForAuction(frozenAmount);
        depositRepository.unfreezeForAuction(deposit.getId(), requestId);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                userId,
                DepositTransaction.TransactionType.UNBLOCK,
                frozenAmount,
                balanceBefore,
                deposit.getAvailableBalance(),
                "DIAGNOSTIC_" + requestId,
                "Разблокировка средств после заявки на диагностику #" + requestId
        );
        depositRepository.saveTransaction(transaction);

        depositRepository.save(deposit);
    }

    @Transactional(readOnly = true)
    public boolean canParticipateInAuction(Long userId, BigDecimal startingPrice) {
        if (startingPrice == null || startingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        return depositRepository.findByUserId(userId)
                .map(deposit -> {
                    BigDecimal required = Deposit.calculateRequiredDeposit(startingPrice);
                    return deposit.getAvailableBalance().compareTo(required) >= 0;
                })
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

    static final BigDecimal MAX_ADJUST_AMOUNT = new BigDecimal("99999999");

    @Transactional
    public DepositResponse adminAdjust(Long targetUserId, BigDecimal amount, String comment) {
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
        depositRepository.saveTransaction(tx);
        Deposit saved = depositRepository.save(deposit);
        log.info("Admin adjusted deposit for user {}: amount={}, comment={}", targetUserId, amount, comment);
        return DepositResponse.fromDomain(saved);
    }
}