// payment/infrastructure/provider/PaymentProviderPortProfileResolutionTest.java
package com.example.autoauction.payment.infrastructure.provider;

import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-closed bootstrap slice - Test A: proves {@link PaymentProviderPort} bean resolution is
 * correct and unambiguous per profile, WITHOUT booting the full application (which requires real
 * Cloudflare R2 credentials via required, no-default {@code @Value} properties on the unrelated
 * {@code R2StorageClient} bean - confirmed by direct source read during this slice's
 * implementation - so a full, non-{@code test}-profile {@code @SpringBootTest} is not achievable
 * here without expanding scope into unrelated storage configuration). This is the "narrowest
 * legitimate Spring context" permitted by the approved plan for this specific proof: a plain
 * {@link ApplicationContextRunner} registering only the two {@link PaymentProviderPort}
 * implementations that exist in this codebase, with no other bean, no database, no web layer.
 *
 * <p>The broader "does the real application boot outside {@code test}" claim is instead
 * established narratively: {@link UnavailablePaymentProviderPort} is the only production
 * {@code PaymentProviderPort} bean, matching the identical {@code @Profile("!test")} idiom already
 * proven to boot correctly by {@code R2StorageClient} in every environment this application
 * actually runs in.
 */
@DisplayName("PaymentProviderPort bean resolution: exactly one unambiguous candidate per profile")
class PaymentProviderPortProfileResolutionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(UnavailablePaymentProviderPort.class, FakePaymentProviderPort.class);

    @Test
    @DisplayName("no 'test' profile active (models any real, non-test environment): " +
            "exactly one PaymentProviderPort bean, and it is UnavailablePaymentProviderPort")
    void nonTestProfile_resolvesUnavailableProviderOnly() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("pg"))
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentProviderPort.class);
                    assertThat(context).hasSingleBean(UnavailablePaymentProviderPort.class);
                    assertThat(context).doesNotHaveBean(FakePaymentProviderPort.class);
                    assertThat(context.getBean(PaymentProviderPort.class))
                            .isInstanceOf(UnavailablePaymentProviderPort.class);
                });
    }

    @Test
    @DisplayName("'test' profile active: exactly one PaymentProviderPort bean, and it is FakePaymentProviderPort")
    void testProfile_resolvesFakeProviderOnly() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentProviderPort.class);
                    assertThat(context).hasSingleBean(FakePaymentProviderPort.class);
                    assertThat(context).doesNotHaveBean(UnavailablePaymentProviderPort.class);
                    assertThat(context.getBean(PaymentProviderPort.class))
                            .isInstanceOf(FakePaymentProviderPort.class);
                });
    }

    @Test
    @DisplayName("both 'test' and 'pg' active (this project's own real-Postgres test convention): " +
            "still exactly one bean, the fake - 'test' alone governs this port's selection")
    void testAndPgProfiles_resolvesFakeProviderOnly() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test", "pg"))
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentProviderPort.class);
                    assertThat(context.getBean(PaymentProviderPort.class))
                            .isInstanceOf(FakePaymentProviderPort.class);
                });
    }
}
