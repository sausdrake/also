package com.example.autoauction.deposit.application;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.deposit.web.dto.DepositTransactionResponse;
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

    public DepositService(DepositRepository depositRepository) {
        this.depositRepository = depositRepository;
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
        log.info("Freezing deposit for user: {}, auction: {}, startingPrice: {}",
                userId, auctionId, startingPrice);

        // Проверка на null и некорректные значения - теперь НЕ выбрасываем исключение
        if (startingPrice == null || startingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid startingPrice: {}. Cannot freeze deposit. Skipping operation.", startingPrice);
            return;  // Просто выходим, ничего не делаем
        }

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        BigDecimal balanceBefore = deposit.getAvailableBalance();
        deposit.freezeForAuction(auctionId, startingPrice);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                userId,
                DepositTransaction.TransactionType.BLOCK,
                Deposit.calculateRequiredDeposit(startingPrice),
                balanceBefore,
                deposit.getAvailableBalance(),
                auctionId.toString(),
                "Блокировка средств для участия в аукционе #" + auctionId
        );
        transaction.setAuctionId(auctionId);
        depositRepository.saveTransaction(transaction);

        depositRepository.save(deposit);
        log.info("Deposit frozen. New available balance: {}", deposit.getAvailableBalance());
    }

    /**
     * Размораживает средства для указанного аукциона (по стартовой цене)
     */
    @Transactional
    public void unfreezeForAuction(Long userId, Long auctionId, BigDecimal startingPrice) {
        log.info("Unfreezing deposit for user: {}, auction: {}, startingPrice: {}",
                userId, auctionId, startingPrice);

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        BigDecimal balanceBefore = deposit.getAvailableBalance();
        deposit.unfreezeForAuction(auctionId, startingPrice, true);

        DepositTransaction transaction = new DepositTransaction(
                deposit.getId(),
                userId,
                DepositTransaction.TransactionType.UNBLOCK,
                Deposit.calculateRequiredDeposit(startingPrice),
                balanceBefore,
                deposit.getAvailableBalance(),
                auctionId.toString(),
                "Разблокировка средств после аукциона #" + auctionId
        );
        transaction.setAuctionId(auctionId);
        depositRepository.saveTransaction(transaction);

        depositRepository.save(deposit);
        log.info("Deposit unfrozen. New available balance: {}", deposit.getAvailableBalance());
    }

    /**
     * Размораживает средства для указанного аукциона (используя сохранённую сумму блокировки)
     * Для обратной совместимости
     */
    @Transactional
    public void unfreezeForAuction(Long userId, Long auctionId) {
        log.info("Unfreezing deposit for user: {}, auction: {} (using stored amount)", userId, auctionId);

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        // Получаем сумму, заблокированную для этого аукциона
        BigDecimal frozenAmount = deposit.getFrozenAmountForAuction(auctionId);

        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("No frozen amount found for auction {}. Nothing to unfreeze.", auctionId);
            return;
        }

        BigDecimal balanceBefore = deposit.getAvailableBalance();
        deposit.unfreezeForAuction(auctionId, frozenAmount);

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
    }

    @Transactional
    public void markAsWinner(Long userId, Long auctionId) {
        log.info("Marking user: {} as winner of auction: {}", userId, auctionId);

        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        deposit.markAsWinner(auctionId);
        depositRepository.save(deposit);

        log.info("User {} marked as winner. Deposit remains frozen for auction {}", userId, auctionId);
    }

    @Transactional(readOnly = true)
    public boolean canParticipateInAuction(Long userId, BigDecimal startingPrice) {
        return depositRepository.findByUserId(userId)
                .map(deposit -> deposit.canParticipateInAuction(startingPrice))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean canParticipateInAuction(Long userId) {
        return depositRepository.findByUserId(userId)
                .map(deposit -> deposit.getAvailableBalance().compareTo(BigDecimal.ZERO) > 0)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public DepositResponse getDepositByUserId(Long userId) {
        Deposit deposit = depositRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        return DepositResponse.fromDomain(deposit);
    }

    @Transactional(readOnly = true)
    public List<DepositTransactionResponse> getTransactionsByUserId(Long userId) {
        log.debug("Getting transactions for user: {}", userId);

        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(userId);
        return transactions.stream()
                .map(DepositTransactionResponse::fromDomain)
                .collect(Collectors.toList());
    }
}