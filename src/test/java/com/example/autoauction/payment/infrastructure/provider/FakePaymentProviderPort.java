package com.example.autoauction.payment.infrastructure.provider;

import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1.4: in-memory {@link PaymentProviderPort} test double, used only under the {@code "test"}
 * profile - no real bank/ELQR adapter exists yet (Phase 3B, explicitly out of scope). Tests
 * program the exact authoritative status a given {@code providerReference} should resolve to via
 * {@link #stubStatus}; an unprogrammed reference resolves to {@code UNKNOWN}, mirroring a real
 * provider that cannot be reached or does not recognise the reference.
 */
@Component
@Profile("test")
public class FakePaymentProviderPort implements PaymentProviderPort {

    private final Map<String, ProviderPaymentStatus> statuses = new ConcurrentHashMap<>();

    /** Test-only: programs the exact status {@link #getPaymentStatus} will return for this reference. */
    public void stubStatus(String providerReference, ProviderPaymentStatus status) {
        statuses.put(providerReference, status);
    }

    /** Test-only: removes any stub for this reference - subsequent calls resolve to UNKNOWN. */
    public void clearStatus(String providerReference) {
        statuses.remove(providerReference);
    }

    @Override
    public PaymentInitiationResult initiatePayment(PaymentInitiationCommand command) {
        throw new UnsupportedOperationException(
                "Payment creation is out of scope for P1.4 - this fake only supports confirmation-path tests");
    }

    @Override
    public ProviderPaymentStatus getPaymentStatus(String providerReference) {
        return statuses.getOrDefault(providerReference,
                new ProviderPaymentStatus(ProviderPaymentState.UNKNOWN, null, null, null));
    }
}
