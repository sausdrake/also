package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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

    public PaymentCreationOrchestrator(PaymentCreationService paymentCreationService,
                                        PaymentProviderPort paymentProviderPort) {
        this.paymentCreationService = paymentCreationService;
        this.paymentProviderPort = paymentProviderPort;
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

        Payment pending = paymentCreationService.finalizeAccepted(payment.getId(), result.providerReference());
        return new PaymentCreationView(pending,
                result.checkoutUrl().orElse(null),
                result.qrPayload().orElse(null));
    }
}
