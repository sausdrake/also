package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositCreditCommand;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentAmountPolicy;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * P1.4: the sole canonical payment-confirmation operation, locked by P1.3. Every future trigger
 * (webhook, reconciliation sweep, manual retry) must call this same operation - never a separate
 * implementation - so the three-layer protection (pessimistic lock, domain transition guards,
 * ledger idempotency) is never bypassed by a second code path.
 *
 * <p><b>This bean owns the ENTIRE transaction boundary for one confirmation attempt</b> -
 * {@link #confirmPayment} is {@code @Transactional} end to end, including the pessimistic lock
 * acquisition and the outbound {@link PaymentProviderPort} call, exactly matching the locked P1.3
 * sequence ("1. load WITH pessimistic lock" before "4. provider status pull"). This is a
 * deliberate, already-locked architectural choice (holding a DB row lock across an outbound
 * call), not something introduced here.
 *
 * <p><b>Must be called only via a genuine cross-bean reference</b> (see
 * {@link PaymentConfirmationOrchestrator}), never via same-class self-invocation. This project has
 * already proven, with a real-PostgreSQL transaction trace, that a self-invoked call to a
 * {@code @Transactional} method silently bypasses the proxy and drops the pessimistic lock's
 * serialization power (the identity-verification approval bug, Phase 4.1/4.2 of this same
 * engagement). This class exists separately from {@link PaymentConfirmationOrchestrator}
 * specifically so that precedent cannot repeat here.
 */
@Slf4j
@Service
public class PaymentConfirmationService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ReconciliationCaseRepository reconciliationCaseRepository;
    private final PaymentProviderPort paymentProviderPort;
    private final DepositService depositService;
    private final Clock clock;

    public PaymentConfirmationService(PaymentRepository paymentRepository,
                                       PaymentEventRepository paymentEventRepository,
                                       ReconciliationCaseRepository reconciliationCaseRepository,
                                       PaymentProviderPort paymentProviderPort,
                                       DepositService depositService,
                                       Clock clock) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.reconciliationCaseRepository = reconciliationCaseRepository;
        this.paymentProviderPort = paymentProviderPort;
        this.depositService = depositService;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * The canonical operation, identified by the Payment's internal id. Resolving a public
     * identifier (e.g. {@code merchantReference}) to this id is the caller's (orchestrator's)
     * job, deliberately done in a SEPARATE transaction/Hibernate session from this method's own -
     * see {@link PaymentConfirmationOrchestrator} for why: an unlocked lookup sharing this
     * method's own session with the locked lookup below would let Hibernate's first-level cache
     * hand back the SAME, stale-field Java object for the locked query's result (same entity id,
     * same session => same cached instance, fields not refreshed from the new query) even though
     * the underlying {@code SELECT ... FOR UPDATE} genuinely blocked at the database level -
     * discovered empirically, via a real-PostgreSQL concurrency test, while implementing this
     * operation.
     *
     * <p>Every branch below is a direct implementation of the P1.3-locked pseudocode. Two
     * corner cases the locked pseudocode did not explicitly enumerate were resolved
     * conservatively, and are called out explicitly rather than silently decided - see the
     * P1.4 implementation report for the full rationale:
     * <ul>
     *   <li>{@code CREATED} (no {@code providerReference} yet - nothing to ask the provider
     *       about) is treated as a safe no-op, the same as an already-terminal status.</li>
     *   <li>Currency mismatch is treated identically to amount mismatch (routed to
     *       {@code ReconciliationCase(MISMATCH)}, zero tolerance) - {@code Payment} already
     *       hard-locks currency to KGS everywhere else, so this is a direct, not invented,
     *       extension of that existing rule.</li>
     * </ul>
     */
    @Transactional
    public Payment confirmPayment(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Платёж не найден: " + paymentId));

        if (payment.getStatus() == PaymentStatus.SUCCEEDED
                || payment.getStatus() == PaymentStatus.FAILED
                || payment.getStatus() == PaymentStatus.CANCELLED) {
            // Already terminal (and not the EXPIRED special case) - safe no-op. Per P1.3/P1.4:
            // do not call the provider unnecessarily once the terminal state is already
            // established locally; nothing was observed this call, so no PaymentEvent either.
            log.info("confirmPayment: payment {} already terminal ({}), no-op", payment.getId(), payment.getStatus());
            return payment;
        }
        if (payment.getStatus() == PaymentStatus.CREATED) {
            // No provider interaction has ever been recorded (providerReference is null) -
            // there is nothing yet to authoritatively confirm. Conservative no-op; not part of
            // the locked pseudocode's explicit enumeration (flagged in the implementation report).
            log.info("confirmPayment: payment {} still CREATED (no providerReference yet), no-op", payment.getId());
            return payment;
        }

        // PENDING or EXPIRED from here - both require an authoritative provider pull.
        PaymentProviderPort.ProviderPaymentStatus providerStatus =
                paymentProviderPort.getPaymentStatus(payment.getProviderReference());

        boolean wasExpired = payment.getStatus() == PaymentStatus.EXPIRED;

        if (providerStatus.state() == PaymentProviderPort.ProviderPaymentState.SUCCEEDED) {
            handleSucceeded(payment, providerStatus);
            return payment;
        }
        if (wasExpired) {
            // EXPIRED + anything other than authoritative SUCCEEDED: preserve EXPIRED, no credit,
            // no resurrection, no transition at all - only the observation is recorded.
            log.info("confirmPayment: payment {} is EXPIRED and provider did not report SUCCEEDED ({}) - preserved",
                    payment.getId(), providerStatus.state());
            appendPollResultIfNotConsecutive(payment);
            return payment;
        }
        if (providerStatus.state() == PaymentProviderPort.ProviderPaymentState.FAILED) {
            handleFailed(payment);
            return payment;
        }
        // PENDING or UNKNOWN: observe only, no transition, safely re-callable later.
        log.info("confirmPayment: payment {} provider state {} - no transition", payment.getId(), providerStatus.state());
        appendPollResultIfNotConsecutive(payment);
        return payment;
    }

    /**
     * Phase 31: technical deduplication only - suppresses a new {@code POLL_RESULT} when the
     * immediately preceding event for this payment is already a {@code POLL_RESULT}, so an
     * unbroken run of non-transition observations (repeated {@code UNKNOWN}/{@code PENDING})
     * persists as exactly one row instead of one per poll. Deliberately does NOT attempt to
     * distinguish {@code UNKNOWN} from {@code PENDING} from the persisted event - both already
     * produce an indistinguishable {@code POLL_RESULT} row ({@code rawPayload} is always null),
     * so this is consecutive-event deduplication, not provider-state deduplication. Any other
     * latest event (a real transition, {@code RECONCILIATION}, or none at all) never suppresses
     * this append - only two consecutive {@code POLL_RESULT} rows are ever collapsed.
     */
    private void appendPollResultIfNotConsecutive(Payment payment) {
        boolean latestIsAlreadyPollResult = paymentEventRepository.findLatestByPaymentId(payment.getId())
                .map(PaymentEvent::getType)
                .filter(type -> type == PaymentEventType.POLL_RESULT)
                .isPresent();
        if (latestIsAlreadyPollResult) {
            return;
        }
        paymentEventRepository.append(new PaymentEvent(
                payment.getId(), PaymentEventType.POLL_RESULT, PaymentEventSource.PROVIDER_POLL, null, now()));
    }

    private void handleSucceeded(Payment payment, PaymentProviderPort.ProviderPaymentStatus providerStatus) {
        boolean amountMatches = PaymentAmountPolicy.amountsMatch(payment.getAmountRequested(), providerStatus.confirmedAmount());
        boolean currencyMatches = Payment.CURRENCY_KGS.equals(providerStatus.currency());

        if (!amountMatches || !currencyMatches) {
            log.warn("confirmPayment: payment {} provider-confirmed amount/currency mismatch "
                            + "(requested={}, confirmed={}, currency={}) - opening MISMATCH case, no credit",
                    payment.getId(), payment.getAmountRequested(), providerStatus.confirmedAmount(), providerStatus.currency());
            // Phase 17: the provider's own observation must survive past this log line - it was
            // previously discarded entirely. Recorded via PaymentEventType.RECONCILIATION,
            // mirroring the unconditional per-occurrence append semantics already used for the
            // sibling POLL_RESULT branches above (one event per provider signal observed by this
            // method, regardless of whether it changes any state) - the same treatment applies
            // here since a mismatch is itself an observed provider signal, not a case-lifecycle
            // event. Deliberately NOT gated on whether a new ReconciliationCase was actually
            // created: a repeated mismatch observation for an already-OPEN case is still a
            // distinct, independently valuable fact for an eventual investigator (was the
            // mismatch consistent across polls, or did the provider's figure itself change?).
            paymentEventRepository.append(new PaymentEvent(payment.getId(), PaymentEventType.RECONCILIATION,
                    PaymentEventSource.PROVIDER_POLL, mismatchEvidence(payment, providerStatus), now()));
            openMismatchCaseIfAbsent(payment);
            return; // Payment remains non-terminal; no markSucceeded; no credit.
        }

        payment.markSucceeded(providerStatus.providerTransactionId(), providerStatus.confirmedAmount());
        paymentRepository.save(payment);

        depositService.creditFromPayment(new DepositCreditCommand(
                payment.getUserId(),
                providerStatus.confirmedAmount(),
                payment.getMerchantReference(),
                "PAYMENT:" + providerStatus.providerTransactionId()));

        paymentEventRepository.append(new PaymentEvent(
                payment.getId(), PaymentEventType.SUCCEEDED, PaymentEventSource.PROVIDER_POLL, null, now()));
        log.info("confirmPayment: payment {} confirmed SUCCEEDED, deposit credited", payment.getId());
    }

    private void handleFailed(Payment payment) {
        payment.markFailed();
        paymentRepository.save(payment);
        paymentEventRepository.append(new PaymentEvent(
                payment.getId(), PaymentEventType.FAILED, PaymentEventSource.PROVIDER_POLL, null, now()));
        log.info("confirmPayment: payment {} confirmed FAILED", payment.getId());
    }

    /**
     * Phase 17: a durable, structured snapshot of what the provider actually reported for a
     * mismatched confirmation - requested vs. provider-confirmed amount/currency, plus the
     * provider's own transaction id (always present here: this is only called from the branch
     * where {@code providerStatus.state() == SUCCEEDED}, the one state for which {@link
     * PaymentProviderPort.ProviderPaymentStatus#providerTransactionId()} is non-null). Plain
     * key=value text, not JSON - {@code rawPayload} has no established structured-format
     * convention elsewhere in this codebase to match, and this keeps the payload legible in a
     * raw DB read without adding a JSON-mapping dependency for a single call site. Amounts use
     * {@link BigDecimal#toPlainString()} - never a floating-point conversion - to preserve exact
     * monetary precision.
     */
    private String mismatchEvidence(Payment payment, PaymentProviderPort.ProviderPaymentStatus providerStatus) {
        return "requestedAmount=" + payment.getAmountRequested().toPlainString()
                + ";requestedCurrency=" + payment.getCurrency()
                + ";providerConfirmedAmount=" + providerStatus.confirmedAmount().toPlainString()
                + ";providerCurrency=" + providerStatus.currency()
                + ";providerTransactionId=" + providerStatus.providerTransactionId()
                + ";providerState=" + providerStatus.state();
    }

    private void openMismatchCaseIfAbsent(Payment payment) {
        // UNIQUE(payment_id, type) - never attempt a second MISMATCH case for the same payment;
        // an existing one (open or already resolved) is left exactly as it is.
        if (reconciliationCaseRepository.findByPaymentIdAndType(payment.getId(), ReconciliationCaseType.MISMATCH).isEmpty()) {
            reconciliationCaseRepository.save(new ReconciliationCase(payment.getId(), ReconciliationCaseType.MISMATCH, now()));
        }
    }

    /**
     * R1: recovery-only entry point for a cross-payment duplicate provider-transaction-identity
     * conflict. Invoked EXCLUSIVELY by {@link PaymentConfirmationOrchestrator}, as a genuine
     * cross-bean call, in a fresh transaction started only after the conflicting {@link
     * #confirmPayment} attempt has already thrown and its own transaction has fully rolled back -
     * never called from within this class, and never called while that original transaction is
     * still open (see {@link PaymentConfirmationOrchestrator} for why that would be unsafe: this
     * bean's own confirmPayment is the transaction owner, so a self-invocation here would silently
     * run inside the doomed transaction instead of a new one).
     *
     * <p>Mirrors {@link #openMismatchCaseIfAbsent}'s dedup semantics exactly - {@code
     * UNIQUE(payment_id, type)} plus an existence check first, so an already-open OR
     * already-resolved {@code DUPLICATE} case is left untouched, never reopened. Deliberately does
     * NOT touch the Payment's lifecycle state and does NOT apply any credit: the conflicting
     * attempt's original transaction already rolled back on its own, so there is no partial
     * Payment/credit state left to reconcile here - only the observation itself needs recording.
     */
    @Transactional
    public void openDuplicateCaseIfAbsent(Long paymentId, String evidence) {
        if (reconciliationCaseRepository.findByPaymentIdAndType(paymentId, ReconciliationCaseType.DUPLICATE).isEmpty()) {
            reconciliationCaseRepository.save(new ReconciliationCase(paymentId, ReconciliationCaseType.DUPLICATE, now()));
        }
        // Deliberately unconditional, same as the sibling mismatchEvidence append above - a
        // repeated duplicate observation for an already-OPEN case is still a distinct,
        // independently valuable fact for an eventual investigator.
        paymentEventRepository.append(new PaymentEvent(
                paymentId, PaymentEventType.RECONCILIATION, PaymentEventSource.PROVIDER_POLL, evidence, now()));
    }
}
