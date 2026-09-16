package com.example.autoauction.payment.infrastructure.provider;

import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-closed bootstrap ({@code Payment Module Fail-Closed Bootstrap} slice): the ONLY
 * {@link PaymentProviderPort} implementation active outside the {@code test} profile until a
 * real, business-selected bank/ELQR adapter exists (none does yet - see
 * {@link PaymentProviderPort}'s own Javadoc). Mirrors this project's already-established
 * {@code @Profile("!test")} idiom (see the diagnostic-media {@code R2StorageClient}/
 * {@code FakeStorageClient} pair) so the application can boot in any real (non-test) environment
 * without pretending a provider is configured.
 *
 * <p><b>Locked financial invariant this class exists to uphold:</b> no payment may become
 * {@code SUCCEEDED} without a genuine, authoritative provider result. This adapter can never
 * produce one - {@link #initiatePayment} never contacts anything and always fails, so no
 * {@code Payment} can ever leave {@code CREATED} (see
 * {@code PaymentCreationOrchestrator#createPayment} - a non-{@code PaymentInitiationRejectedException}
 * {@code RuntimeException} here leaves the Payment {@code CREATED}, calls neither
 * {@code finalizeAccepted} nor {@code finalizeRejected}); and {@link #getPaymentStatus} never
 * reports {@link PaymentProviderPort.ProviderPaymentState#SUCCEEDED}, so even a hypothetical stray
 * confirmation call (see {@code PaymentConfirmationService#confirmPayment}, which already no-ops
 * on {@code CREATED} with no {@code providerReference}) could never credit a deposit through this
 * adapter. It touches no repository, no application service, and no {@code Payment}/{@code Deposit}
 * state directly - a pure boundary object, deliberately as small as the port it implements.
 */
@Slf4j
@Component
@Profile("!test")
public class UnavailablePaymentProviderPort implements PaymentProviderPort {

    @Override
    public PaymentInitiationResult initiatePayment(PaymentInitiationCommand command) {
        log.warn("Payment initiation attempted for merchantReference={} but no real payment provider "
                + "is configured (fail-closed bootstrap active) - leaving Payment CREATED", command.merchantReference());
        throw new PaymentProviderUnavailableException(
                "No real payment provider is configured; payment initiation cannot proceed.");
    }

    @Override
    public ProviderPaymentStatus getPaymentStatus(String providerReference) {
        log.warn("Payment status pull attempted for providerReference={} but no real payment provider "
                + "is configured (fail-closed bootstrap active) - reporting UNKNOWN", providerReference);
        return new ProviderPaymentStatus(ProviderPaymentState.UNKNOWN, null, null, null);
    }
}
