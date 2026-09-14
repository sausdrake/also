// deposit/application/DepositUnfreezeTransactionService.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.FrozenDeposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * D4 defect fix (PLAN/VERIFY #3, Option B): owns ONLY the atomic unfreeze mutation transaction,
 * extracted out of {@link DepositService#unfreezeForAuction} for the same reason
 * {@code AdminIdentityApprovalOrchestrator}'s Javadoc documents for the identical bug class in the
 * identity module - a same-bean {@code this.method()} call silently bypasses the Spring
 * transactional proxy, so the isolated {@code REQUIRES_NEW} mutation must live on a genuinely
 * separate bean, called through DepositService's ordinary (proxied) dependency reference.
 *
 * <p>This lets {@code DepositService.unfreezeForAuction} call this bean's method as a plain Java
 * call from OUTSIDE any transaction of its own: if this method's own {@code REQUIRES_NEW}
 * transaction fails to commit (e.g. a genuine {@code @Version} optimistic-lock conflict on the
 * Deposit row, detected by {@link DepositRepository#saveAndFlush}), whatever exception surfaces -
 * including a possible {@code UnexpectedRollbackException} from this bean's own proxy - is thrown
 * synchronously as part of that single call, fully catchable by {@code unfreezeForAuction}'s own
 * try/catch, which never had a transaction of its own to be poisoned by it.
 *
 * <p>Deliberately does NOT depend on {@code FailedOperationService} or {@code NotificationService} -
 * failure recording and notification are orchestration concerns that stay in
 * {@code DepositService}. This class's only job is the atomic Deposit/FrozenDeposit/ledger write.
 */
@Slf4j
@Service
public class DepositUnfreezeTransactionService {

    private final DepositRepository depositRepository;

    public DepositUnfreezeTransactionService(DepositRepository depositRepository) {
        this.depositRepository = depositRepository;
    }

    /**
     * @return the amount that was unfrozen, or {@link Optional#empty()} if there was nothing
     *         frozen for this (userId, auctionId) - not an error, mirrors the pre-existing no-op
     *         behavior (an auction may complete without ever receiving bids).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<BigDecimal> unfreeze(Long userId, Long auctionId) {
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
            return Optional.empty();
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

        // D4 defect fix: saveAndFlush (not save) so a genuine @Version conflict throws HERE,
        // inside this method's own REQUIRES_NEW transaction, before it returns to the caller.
        depositRepository.saveAndFlush(deposit);
        log.info("Deposit unfrozen for auction {}. New available balance: {}", auctionId, deposit.getAvailableBalance());

        return Optional.of(frozenAmount);
    }
}
