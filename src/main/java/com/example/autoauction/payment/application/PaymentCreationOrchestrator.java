package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * P1.5: the single public entry point for payment creation. Mirrors
 * {@code PaymentConfirmationOrchestrator}'s already-proven two-bean shape (P1.4), reapplied here
 * for the same two independent reasons:
 * <ol>
 *   <li>{@link PaymentCreationService#createPayment}/{@link PaymentCreationService#finalizeAccepted}/
 *       {@link PaymentCreationService#finalizeRejected} are the transaction owners; this class is
 *       deliberately NOT {@code @Transactional} and must never become so.</li>
 *   <li>The outbound {@link PaymentProviderPort#initiatePayment} call happens here, strictly
 *       BETWEEN the two database transactions (TX1 already committed, its Deposit lock already
 *       released, before this call; TX2 opens only after it returns) - never inside either
 *       transaction. Holding the Deposit lock across a non-idempotent, unpredictable-latency
 *       provider call was explicitly rejected during the P1.5 architecture challenge (unlike
 *       confirmation's idempotent, low-risk status-pull, which P1.4 does hold its lock across).</li>
 * </ol>
 *
 * <p><b>Provider response handling is exactly the locked three-way split</b> - accepted,
 * explicitly rejected, or ambiguous - and nothing else:
 * <ul>
 *   <li>{@link PaymentProviderPort.PaymentInitiationRejectedException} = explicit synchronous
 *       rejection -> {@link PaymentCreationService#finalizeRejected}.</li>
 *   <li>Any other exception (timeout, connection failure, unrecognised result) = ambiguous ->
 *       left {@code CREATED}, no TX2 call at all. Never retried automatically, never assumed
 *       idempotent on the provider's side - the port gives no such guarantee. The existing P1.5
 *       expiry sweep eventually resolves a permanently-stuck case; recovering a genuinely
 *       orphaned bank-side payment is a disclosed, provider-dependent residual limitation, not
 *       solved here.</li>
 *   <li>Normal return = accepted -> {@link PaymentCreationService#finalizeAccepted}.</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentCreationOrchestrator {

    private final PaymentCreationService paymentCreationService;
    private final PaymentProviderPort paymentProviderPort;
    private final PaymentEventRepository paymentEventRepository;

    public PaymentCreationOrchestrator(PaymentCreationService paymentCreationService,
                                        PaymentProviderPort paymentProviderPort,
                                        PaymentEventRepository paymentEventRepository) {
        this.paymentCreationService = paymentCreationService;
        this.paymentProviderPort = paymentProviderPort;
        this.paymentEventRepository = paymentEventRepository;
    }

    public PaymentCreationView createPayment(Long userId, BigDecimal amountRequested, String idempotencyKey) {
        PaymentCreationOutcome outcome;
        try {
            outcome = paymentCreationService.createPayment(userId, amountRequested, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            // §8: the attempt transaction is already rolled back at this point (Spring's
            // transactional advice does that on the way out of createPayment). Resolving the
            // race requires a genuinely fresh transaction - see
            // PaymentCreationService.resolveConflictAfterRace's own Javadoc for why this cannot
            // happen inside the same transaction that just failed.
            PaymentCreationOutcome resolved = paymentCreationService.resolveConflictAfterRace(userId, amountRequested, idempotencyKey);
            if (resolved == null) {
                throw e;
            }
            outcome = resolved;
        }
        Payment payment = outcome.payment();

        if (!outcome.isNewCreation()) {
            // Idempotent replay: the provider call (if any) already happened, or will happen, on
            // the ORIGINAL request that first created this Payment - never re-call it here.
            // Transient presentation data (checkoutUrl/qrPayload) from that original call is not
            // persisted anywhere and cannot be reproduced - disclosed limitation, not a bug.
            return PaymentCreationView.withoutPresentationData(payment);
        }

        PaymentProviderPort.PaymentInitiationCommand command = new PaymentProviderPort.PaymentInitiationCommand(
                payment.getMerchantReference(), payment.getAmountRequested(), payment.getCurrency(), null);

        PaymentProviderPort.PaymentInitiationResult result;
        try {
            result = paymentProviderPort.initiatePayment(command);
        } catch (PaymentProviderPort.PaymentInitiationRejectedException e) {
            log.info("Payment {} initiation explicitly rejected by provider: {}", payment.getId(), e.getMessage());
            Payment rejected = paymentCreationService.finalizeRejected(payment.getId());
            return PaymentCreationView.withoutPresentationData(rejected);
        } catch (RuntimeException e) {
            log.warn("Payment {} initiation ambiguous (left CREATED): {}", payment.getId(), e.getMessage());
            return PaymentCreationView.withoutPresentationData(payment);
        }

        // Phase 33: durable checkpoint - record the accepted providerReference BEFORE attempting
        // the CREATED -> PENDING transition, so a crash/rollback in that specific window can
        // still be recovered later (see recoverFromInitiationEvidence). Never called on explicit
        // rejection or ambiguous failure - only on a normal, successful provider return.
        paymentCreationService.recordInitiationAccepted(payment.getId(), result.providerReference());

        Payment pending = paymentCreationService.finalizeAccepted(payment.getId(), result.providerReference());
        return new PaymentCreationView(pending,
                result.checkoutUrl().orElse(null),
                result.qrPayload().orElse(null));
    }

    /**
     * Phase 33: recovers a {@code CREATED} payment whose {@code PaymentProviderPort#initiatePayment}
     * call genuinely succeeded - durably evidenced by an {@code INITIATION_ACCEPTED} event written
     * before {@link PaymentCreationService#finalizeAccepted} was ever attempted - but whose own
     * {@code finalizeAccepted} transition never committed (a process crash or transaction failure
     * in that window). Never calls {@link PaymentProviderPort} again; only ever completes the
     * existing, already-guarded {@link PaymentCreationService#finalizeAccepted} using a
     * {@code providerReference} this application already durably received once. Reuses that
     * method's own pessimistic lock and {@code status != CREATED} no-op guard for concurrency
     * safety - no new locking is introduced here.
     *
     * <p>Does NOT solve the case where the provider's response was itself lost (a timeout with no
     * {@code PaymentInitiationResult} ever returned to this application) - no evidence exists to
     * recover from in that case, and this method has nothing to act on. That remains a disclosed,
     * unresolved limitation requiring a provider-side lookup capability this codebase does not
     * have.
     *
     * <p>Phase 35: parsing/validation of the evidence is delegated entirely to {@link
     * InitiationEvidenceClassifier} - the same pure classifier {@code AdminPaymentService} uses
     * for read-only observability, so the two can never disagree. {@code Malformed}/{@code
     * Conflicting} evidence throws {@link PermanentInitiationEvidenceException} - a distinct type
     * from the {@code IllegalStateException} thrown for the (structurally-should-not-happen-for-a
     * -real-candidate) {@code None} case - never mutates {@code Payment}, never appends an event,
     * never calls {@link PaymentProviderPort} or {@code DepositService}, never creates a
     * {@code ReconciliationCase}.
     *
     * @throws IllegalStateException if no evidence exists for this payment
     * @throws PermanentInitiationEvidenceException if the evidence is malformed or multiple
     *         evidence events disagree on the providerReference (fail-closed: never guesses)
     */
    public Payment recoverFromInitiationEvidence(Long paymentId) {
        List<PaymentEvent> evidence = paymentEventRepository.findByPaymentIdAndType(
                paymentId, PaymentEventType.INITIATION_ACCEPTED);
        InitiationEvidenceClassification classification = InitiationEvidenceClassifier.classify(evidence);

        if (classification instanceof InitiationEvidenceClassification.None) {
            throw new IllegalStateException(
                    "Платёж " + paymentId + " не имеет свидетельства инициации для восстановления");
        }
        if (classification instanceof InitiationEvidenceClassification.Malformed malformed) {
            throw new PermanentInitiationEvidenceException(
                    "Платёж " + paymentId + " имеет некорректное свидетельство инициации: " + malformed.reason());
        }
        if (classification instanceof InitiationEvidenceClassification.Conflicting conflicting) {
            throw new PermanentInitiationEvidenceException(
                    "Платёж " + paymentId + " имеет конфликтующие свидетельства инициации: "
                            + conflicting.providerReferences() + " - восстановление отклонено");
        }

        String providerReference = ((InitiationEvidenceClassification.Valid) classification).providerReference();
        return paymentCreationService.finalizeAccepted(paymentId, providerReference);
    }
}
