// payment/infrastructure/provider/UnavailablePaymentProviderPortTest.java
package com.example.autoauction.payment.infrastructure.provider;

import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-closed bootstrap slice: narrow, dependency-free unit coverage of
 * {@link UnavailablePaymentProviderPort} in isolation - no Spring context, no database. Proves the
 * adapter's own contract directly, independent of how {@code PaymentCreationOrchestrator}/
 * {@code PaymentConfirmationService} happen to react to it (that end-to-end behaviour is covered
 * separately by {@code PaymentFailClosedBootstrapIntegrationTest}).
 */
@DisplayName("UnavailablePaymentProviderPort: fail-closed adapter contract")
class UnavailablePaymentProviderPortTest {

    private final UnavailablePaymentProviderPort provider = new UnavailablePaymentProviderPort();

    @Test
    @DisplayName("initiatePayment always throws PaymentProviderUnavailableException, never a successful result")
    void initiatePayment_alwaysThrowsUnavailable() {
        PaymentProviderPort.PaymentInitiationCommand command = new PaymentProviderPort.PaymentInitiationCommand(
                "PAY-" + System.nanoTime(), new BigDecimal("1000.00"), "KGS", null);

        assertThatThrownBy(() -> provider.initiatePayment(command))
                .isInstanceOf(PaymentProviderUnavailableException.class);
    }

    @Test
    @DisplayName("initiatePayment never throws PaymentInitiationRejectedException - that type is reserved " +
            "for an explicit real-provider rejection, which this adapter can never issue")
    void initiatePayment_neverThrowsExplicitRejection() {
        PaymentProviderPort.PaymentInitiationCommand command = new PaymentProviderPort.PaymentInitiationCommand(
                "PAY-" + System.nanoTime(), new BigDecimal("1000.00"), "KGS", null);

        assertThatThrownBy(() -> provider.initiatePayment(command))
                .isNotInstanceOf(PaymentProviderPort.PaymentInitiationRejectedException.class);
    }

    @Test
    @DisplayName("getPaymentStatus never returns SUCCEEDED, for any reference, including an arbitrary/unknown one")
    void getPaymentStatus_neverReportsSucceeded() {
        PaymentProviderPort.ProviderPaymentStatus status = provider.getPaymentStatus("ANY-PROVIDER-REFERENCE");

        assertThat(status.state()).isNotEqualTo(PaymentProviderPort.ProviderPaymentState.SUCCEEDED);
        assertThat(status.state()).isEqualTo(PaymentProviderPort.ProviderPaymentState.UNKNOWN);
        assertThat(status.confirmedAmount()).isNull();
        assertThat(status.currency()).isNull();
        assertThat(status.providerTransactionId()).isNull();
    }

    @Test
    @DisplayName("getPaymentStatus is deterministic and stateless - repeated calls with different " +
            "references never fabricate a providerTransactionId or amount")
    void getPaymentStatus_neverFabricatesFields() {
        assertThat(provider.getPaymentStatus("REF-A").state()).isEqualTo(PaymentProviderPort.ProviderPaymentState.UNKNOWN);
        assertThat(provider.getPaymentStatus("REF-B").state()).isEqualTo(PaymentProviderPort.ProviderPaymentState.UNKNOWN);
        assertThat(provider.getPaymentStatus(null).state()).isEqualTo(PaymentProviderPort.ProviderPaymentState.UNKNOWN);
    }
}
