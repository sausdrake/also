package com.example.autoauction.deposit.application;

import com.example.autoauction.deposit.domain.WithdrawalRequest;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.domain.port.WithdrawalRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * F6: withdrawal as a MANUAL PAYOUT REQUEST - until a bank is connected the user never receives
 * an automatic payout; an admin pays by hand and records it here.
 *
 * <p>No second ledger: money moves only through {@link DepositService}.
 * <ul>
 *   <li>{@link #create} debits the amount with the existing {@link DepositService#withdraw}
 *       primitive (available-balance check, {@code @Version}, unique idempotency key), so from
 *       that moment it is unavailable to another withdrawal, an auction or diagnostic freeze, a
 *       charge - to anything that reads the available balance.</li>
 *   <li>{@link #reject} returns it with {@link DepositService#returnWithdrawal}, whose ledger key
 *       {@code WITHDRAWAL_RETURN:<id>} is unique in the database - at most one return, ever.</li>
 *   <li>{@link #startProcessing} and {@link #markPaid} move no money; PAID only records that AlSo
 *       paid the user by hand (with a mandatory note on how). It is never a bank confirmation.</li>
 * </ul>
 * Every admin transition locks the request row first; the domain refuses any transition out of
 * the wrong status, so a repeated or late action is a 409, never a second effect. PAID and
 * REJECTED are both final, so for one PROCESSING request they are mutually exclusive: whichever
 * transaction takes the row lock second sees the other's terminal status and is refused.
 */
@Slf4j
@Service
public class WithdrawalRequestService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    /**
     * Ledger keys the server itself writes. A client key with one of these prefixes could occupy
     * a key a later server operation needs (and so block a rejection's return), so it is refused.
     */
    private static final List<String> RESERVED_KEY_PREFIXES =
            List.of("PAYMENT:", DepositService.WITHDRAWAL_RETURN_KEY_PREFIX);

    private final WithdrawalRequestRepository withdrawalRequestRepository;
    private final DepositRepository depositRepository;
    private final DepositService depositService;
    private final Clock clock;

    public WithdrawalRequestService(WithdrawalRequestRepository withdrawalRequestRepository,
                                    DepositRepository depositRepository,
                                    DepositService depositService,
                                    Clock clock) {
        this.withdrawalRequestRepository = withdrawalRequestRepository;
        this.depositRepository = depositRepository;
        this.depositService = depositService;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Creates the request and debits the amount, all-or-nothing. A retry with the same key, user
     * and amount returns the original request without a second debit; the same key with another
     * user or amount is a 409.
     */
    @Transactional
    public WithdrawalRequest create(Long userId, BigDecimal amount, String rawIdempotencyKey) {
        String key = normalizeKey(rawIdempotencyKey);

        // Serialises every withdrawal of this user: the idempotency checks below and the debit
        // all run under the deposit row lock.
        depositRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        var existing = withdrawalRequestRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            WithdrawalRequest prev = existing.get();
            if (!prev.getUserId().equals(userId) || amount == null || prev.getAmount().compareTo(amount) != 0) {
                throw new IllegalStateException("Ключ идемпотентности '" + key + "' уже использован для другой операции "
                        + "(другая сумма или пользователь)");
            }
            log.info("Idempotent replay of withdrawal request {} for key '{}', user {}", prev.getId(), key, userId);
            return prev;
        }
        // A ledger row with this key but no request: a withdrawal made before F6, or another
        // operation. Creating a request for it would let a rejection "return" money that this
        // request never debited.
        if (depositRepository.findTransactionByIdempotencyKey(key).isPresent()) {
            throw new IllegalStateException("Ключ идемпотентности '" + key + "' уже использован для другой операции");
        }

        WithdrawalRequest saved = withdrawalRequestRepository.save(new WithdrawalRequest(userId, amount, key, now()));
        depositService.withdraw(userId, amount, key, DepositService.withdrawalReference(saved.getId()),
                "Вывод средств: заявка #" + saved.getId() + " (ручная выплата)");
        log.info("Withdrawal request {} created for user {}: amount={}", saved.getId(), userId, amount);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<WithdrawalRequest> listMine(Long userId) {
        return withdrawalRequestRepository.findByUserIdNewestFirst(userId);
    }

    @Transactional(readOnly = true)
    public List<WithdrawalRequest> listAll(WithdrawalRequestStatus status) {
        return withdrawalRequestRepository.findAllNewestFirst(status);
    }

    /** PENDING -> PROCESSING. No money moves. */
    @Transactional
    public WithdrawalRequest startProcessing(Long requestId, Long adminId) {
        WithdrawalRequest request = lock(requestId);
        request.startProcessing(adminId, now());
        WithdrawalRequest saved = withdrawalRequestRepository.save(request);
        log.info("Withdrawal request {} taken into processing by admin {}", requestId, adminId);
        return saved;
    }

    /** PROCESSING -> PAID: the admin confirms the manual payout and notes how. No money moves. */
    @Transactional
    public WithdrawalRequest markPaid(Long requestId, Long adminId, String payoutNote) {
        WithdrawalRequest request = lock(requestId);
        request.markPaid(adminId, payoutNote, now());
        WithdrawalRequest saved = withdrawalRequestRepository.save(request);
        log.info("Withdrawal request {} marked PAID (manual payout) by admin {}", requestId, adminId);
        return saved;
    }

    /**
     * PENDING or PROCESSING -> REJECTED and the amount back to the deposit, in one transaction.
     * {@code expectedStatus} is the status the admin saw when deciding: it is compared under the
     * row lock, so a request that was taken into work (and perhaps paid out) after the admin's
     * screen was rendered is refused instead of being rejected on a stale view.
     */
    @Transactional
    public WithdrawalRequest reject(Long requestId, Long adminId, String reason, boolean payoutNotMadeConfirmed,
                                    WithdrawalRequestStatus expectedStatus) {
        if (expectedStatus == null) {
            throw new IllegalArgumentException("Ожидаемый статус заявки обязателен");
        }
        WithdrawalRequest request = lock(requestId);
        if (request.getStatus() != expectedStatus) {
            throw new IllegalStateException("Статус заявки на вывод #" + requestId + " изменился: ожидался "
                    + expectedStatus + ", сейчас " + request.getStatus() + ". Обновите страницу.");
        }
        request.reject(adminId, reason, payoutNotMadeConfirmed, now());
        WithdrawalRequest saved = withdrawalRequestRepository.save(request);
        depositService.returnWithdrawal(saved.getUserId(), saved.getId(), saved.getAmount());
        log.info("Withdrawal request {} rejected by admin {}, amount {} returned", requestId, adminId, saved.getAmount());
        return saved;
    }

    private WithdrawalRequest lock(Long requestId) {
        return withdrawalRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка на вывод не найдена: " + requestId));
    }

    private static String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Ключ идемпотентности обязателен для вывода средств");
        }
        String key = raw.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Ключ идемпотентности не может быть длиннее " + MAX_IDEMPOTENCY_KEY_LENGTH + " символов");
        }
        for (String prefix : RESERVED_KEY_PREFIXES) {
            if (key.startsWith(prefix)) {
                throw new IllegalArgumentException("Недопустимый ключ идемпотентности");
            }
        }
        return key;
    }
}
