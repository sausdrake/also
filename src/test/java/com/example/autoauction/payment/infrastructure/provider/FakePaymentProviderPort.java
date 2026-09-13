package com.example.autoauction.payment.infrastructure.provider;

import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1.4/P1.5: in-memory {@link PaymentProviderPort} test double, used only under the
 * {@code "test"} profile - no real bank/ELQR adapter exists (explicitly out of scope for both
 * slices). Tests program:
 * <ul>
 *   <li>{@link #stubStatus} - the authoritative status a given {@code providerReference} should
 *       resolve to via {@link #getPaymentStatus} (P1.4, confirmation path); an unprogrammed
 *       reference resolves to {@code UNKNOWN}, mirroring a real provider that cannot be reached
 *       or does not recognise the reference.</li>
 *   <li>{@link #stubInitiationAccepted}/{@link #stubInitiationRejected}/
 *       {@link #stubInitiationAmbiguous} - how {@link #initiatePayment} resolves for a given
 *       {@code merchantReference} (P1.5, creation path); an unprogrammed reference is treated as
 *       ambiguous (throws a plain {@code RuntimeException}), mirroring the same
 *       "no information available" default used for confirmation above.</li>
 * </ul>
 */
@Component
@Profile("test")
public class FakePaymentProviderPort implements PaymentProviderPort {

    private final Map<String, ProviderPaymentStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, String> acceptedInitiations = new ConcurrentHashMap<>();
    private final Set<String> rejectedInitiations = ConcurrentHashMap.newKeySet();
    private final Set<String> ambiguousInitiations = ConcurrentHashMap.newKeySet();

    /**
     * Test-only: when set, a merchantReference with no explicit per-reference stub falls back to
     * this behaviour instead of ambiguous - lets a test drive a true end-to-end orchestrator call
     * (whose merchantReference is generated internally and not knowable beforehand) without
     * needing to learn the reference first.
     */
    private volatile String defaultAcceptedProviderReference;
    private volatile boolean defaultRejected;

    /** Test-only: programs the exact status {@link #getPaymentStatus} will return for this reference. */
    public void stubStatus(String providerReference, ProviderPaymentStatus status) {
        statuses.put(providerReference, status);
    }

    /** Test-only: removes any stub for this reference - subsequent calls resolve to UNKNOWN. */
    public void clearStatus(String providerReference) {
        statuses.remove(providerReference);
    }

    /** Test-only: {@link #initiatePayment} for this merchantReference returns an accepted result. */
    public void stubInitiationAccepted(String merchantReference, String providerReference) {
        acceptedInitiations.put(merchantReference, providerReference);
    }

    /** Test-only: {@link #initiatePayment} for this merchantReference throws {@link PaymentInitiationRejectedException}. */
    public void stubInitiationRejected(String merchantReference) {
        rejectedInitiations.add(merchantReference);
    }

    /** Test-only: {@link #initiatePayment} for this merchantReference throws a plain (ambiguous) {@code RuntimeException}. */
    public void stubInitiationAmbiguous(String merchantReference) {
        ambiguousInitiations.add(merchantReference);
    }

    /** Test-only: any merchantReference with no explicit stub is accepted with this providerReference. */
    public void stubDefaultAccepted(String providerReference) {
        this.defaultAcceptedProviderReference = providerReference;
        this.defaultRejected = false;
    }

    /** Test-only: any merchantReference with no explicit stub is explicitly rejected. */
    public void stubDefaultRejected() {
        this.defaultRejected = true;
        this.defaultAcceptedProviderReference = null;
    }

    /** Test-only: resets the default fallback to ambiguous (the original, most conservative default). */
    public void clearDefaultStub() {
        this.defaultAcceptedProviderReference = null;
        this.defaultRejected = false;
    }

    @Override
    public PaymentInitiationResult initiatePayment(PaymentInitiationCommand command) {
        String ref = command.merchantReference();
        if (rejectedInitiations.contains(ref)) {
            throw new PaymentInitiationRejectedException("Stubbed explicit rejection for " + ref);
        }
        if (ambiguousInitiations.contains(ref)) {
            throw new RuntimeException("Stubbed ambiguous failure for " + ref);
        }
        String providerReference = acceptedInitiations.get(ref);
        if (providerReference != null) {
            return new PaymentInitiationResult(providerReference, Optional.empty(), Optional.empty());
        }
        if (defaultRejected) {
            throw new PaymentInitiationRejectedException("Default-stubbed explicit rejection for " + ref);
        }
        if (defaultAcceptedProviderReference != null) {
            return new PaymentInitiationResult(defaultAcceptedProviderReference, Optional.empty(), Optional.empty());
        }
        throw new RuntimeException("No initiation stub programmed for merchantReference: " + ref);
    }

    @Override
    public ProviderPaymentStatus getPaymentStatus(String providerReference) {
        return statuses.getOrDefault(providerReference,
                new ProviderPaymentStatus(ProviderPaymentState.UNKNOWN, null, null, null));
    }
}
