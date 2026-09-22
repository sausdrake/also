package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.domain.LedgerIdempotencyConflictException;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * P1.4: the single public entry point for payment confirmation. Webhook-triggered confirmation,
 * reconciliation-triggered confirmation, and any future manual retry must all call
 * {@link #confirmPayment} on THIS bean - never {@link PaymentConfirmationService} directly from
 * a caller that also happens to share a class with it, and never a second, independent
 * confirmation implementation.
 *
 * <p>Deliberately a separate bean from {@link PaymentConfirmationService}, not a convenience
 * wrapper that could be collapsed into it, for two independent reasons discovered/confirmed while
 * implementing P1.4:
 * <ol>
 *   <li>{@link PaymentConfirmationService#confirmPayment} is the transaction owner; this class is
 *       NOT {@code @Transactional} and must never become so - if it were, and it ever needed to
 *       catch a failure from the service and run independent recovery logic in a fresh
 *       transaction (the same shape as {@code AdminIdentityApprovalOrchestrator} /
 *       {@code AdminIdentityVerificationService.recordFailedApprovalAttempt}, Phase 4.2 of this
 *       engagement), that recovery call would itself need to be a genuine cross-bean call -
 *       impossible if this orchestrator and the service it calls were ever merged back into one
 *       class.</li>
 *   <li>The {@code merchantReference -> id} lookup below MUST run in its own, separate
 *       transaction/Hibernate session from the locked confirmation itself - sharing one session
 *       between an unlocked lookup and the subsequent locked re-read let Hibernate's
 *       first-level cache hand back the same, stale-field Java object for the second query
 *       (discovered empirically via a real-PostgreSQL concurrency test). Calling
 *       {@code paymentRepository.findByMerchantReference} from this non-transactional bean gives
 *       it its own short-lived mini-transaction, fully closed before
 *       {@link PaymentConfirmationService#confirmPayment} ever opens its own.</li>
 * </ol>
 *
 * <p>Beyond resolving the reference, this slice's canonical operation resolves an already-terminal
 * Payment with a safe no-op (see {@link PaymentConfirmationService#confirmPayment}), not an
 * exception, so there is no losing-race failure here that needs REQUIRES_NEW recovery the way
 * the identity-approval case did. The two-bean separation is kept anyway, structurally, because
 * P1.3 explicitly locks it as a requirement, not an optimization to defer - a future slice (e.g.
 * the deferred DUPLICATE-provider-identity reconciliation case) may need exactly this shape, and
 * it must already exist as two beans when that need arrives.
 *
 * <p>R1: the deferred DUPLICATE-provider-identity case referenced above is now implemented here -
 * {@link #confirmPayment} catches the conflict AFTER {@link PaymentConfirmationService#confirmPayment}
 * has already thrown and its transaction has fully rolled back, then makes exactly the kind of
 * genuine cross-bean recovery call reason (1) above reserved this two-bean shape for.
 */
@Slf4j
@Component
public class PaymentConfirmationOrchestrator {

    private final PaymentConfirmationService paymentConfirmationService;
    private final PaymentRepository paymentRepository;

    public PaymentConfirmationOrchestrator(PaymentConfirmationService paymentConfirmationService,
                                            PaymentRepository paymentRepository) {
        this.paymentConfirmationService = paymentConfirmationService;
        this.paymentRepository = paymentRepository;
    }

    public Payment confirmPayment(String merchantReference) {
        Payment found = paymentRepository.findByMerchantReference(merchantReference)
                .orElseThrow(() -> new IllegalArgumentException("Платёж не найден: " + merchantReference));
        try {
            return paymentConfirmationService.confirmPayment(found.getId());
        } catch (LedgerIdempotencyConflictException | DataIntegrityViolationException e) {
            // R1: LedgerIdempotencyConflictException is the dominant, sequential-duplicate case
            // (DepositService's own ledger-idempotency-key fingerprint check, thrown before any DB
            // flush occurs). DataIntegrityViolationException is the narrower, genuinely-concurrent
            // race where two conflicting flushes both reach Postgres before either commits -
            // uk_payment_provider_transaction_id or uk_deposit_transactions_idempotency_key, the
            // only two unique constraints reachable from this transaction's write-set, so this
            // catch cannot misclassify an unrelated integrity failure as a duplicate. By the time
            // this catch runs, paymentConfirmationService.confirmPayment's own @Transactional
            // method has already fully rolled back (Spring marks it rollback-only the instant the
            // exception crosses creditFromPayment's transactional boundary) - so recording the
            // DUPLICATE case below is a genuinely separate, fresh transaction, never a continuation
            // of the failed one.
            log.warn("confirmPayment: duplicate provider-transaction identity detected for payment {} "
                            + "(merchantReference={}): {}",
                    found.getId(), merchantReference, e.getMessage());
            paymentConfirmationService.openDuplicateCaseIfAbsent(found.getId(),
                    "conflictType=" + e.getClass().getSimpleName() + ";detail=" + e.getMessage());
            return found;
        }
    }
}
