package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** CFG-01: the prod profile refuses development-only configuration at startup. */
@DisplayName("CFG-01: ProductionProfileGuard")
class ProductionProfileGuardTest {

    private static final String GOOD_SECRET = "prod-secret-" + "x".repeat(40);
    private static final String GOOD_ORIGINS = "https://also.example, https://www.also.example";
    private static final String GOOD_MEDIA = "https://also.example/uploads";

    private static List<String> check(List<String> profiles, String secret, String origins, String media) {
        return ProductionProfileGuard.check(profiles, secret, origins, media);
    }

    @Test
    @DisplayName("a proper production configuration passes")
    void goodConfiguration_passes() {
        assertThat(check(List.of("prod"), GOOD_SECRET, GOOD_ORIGINS, GOOD_MEDIA)).isEmpty();
    }

    @Test
    @DisplayName("dev or test profile next to prod is refused")
    void devOrTestProfile_refused() {
        assertThat(check(List.of("prod", "dev"), GOOD_SECRET, GOOD_ORIGINS, GOOD_MEDIA)).anyMatch(p -> p.contains("'dev'"));
        assertThat(check(List.of("test", "prod"), GOOD_SECRET, GOOD_ORIGINS, GOOD_MEDIA)).anyMatch(p -> p.contains("'test'"));
    }

    @Test
    @DisplayName("a JWT secret shorter than 32 bytes, missing or blank is refused")
    void weakSecret_refused() {
        assertThat(check(List.of("prod"), "x".repeat(31), GOOD_ORIGINS, GOOD_MEDIA)).anyMatch(p -> p.contains("32 bytes"));
        assertThat(check(List.of("prod"), null, GOOD_ORIGINS, GOOD_MEDIA)).anyMatch(p -> p.contains("32 bytes"));
        assertThat(check(List.of("prod"), "x".repeat(32), GOOD_ORIGINS, GOOD_MEDIA)).isEmpty();
    }

    @Test
    @DisplayName("the JWT secret shipped for tests is a public value and can never pass in prod")
    void testProfileSecret_refused() throws Exception {
        Properties test = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-test.properties"));
        String testSecret = test.getProperty("jwt.secret");
        assertThat(testSecret).isNotBlank();

        assertThat(check(List.of("prod"), testSecret, GOOD_ORIGINS, GOOD_MEDIA))
                .anyMatch(p -> p.contains("publicly known") || p.contains("32 bytes"));
    }

    @Test
    @DisplayName("origins: wildcard, localhost, plain http and an empty list are refused")
    void badOrigins_refused() {
        assertThat(check(List.of("prod"), GOOD_SECRET, "*", GOOD_MEDIA)).anyMatch(p -> p.contains("wildcard"));
        assertThat(check(List.of("prod"), GOOD_SECRET, "https://*.also.example", GOOD_MEDIA)).anyMatch(p -> p.contains("wildcard"));
        assertThat(check(List.of("prod"), GOOD_SECRET, "https://also.example,http://localhost:5173", GOOD_MEDIA))
                .anyMatch(p -> p.contains("localhost:5173"));
        assertThat(check(List.of("prod"), GOOD_SECRET, "http://also.example", GOOD_MEDIA)).anyMatch(p -> p.contains("https"));
        assertThat(check(List.of("prod"), GOOD_SECRET, "https://127.0.0.1", GOOD_MEDIA)).anyMatch(p -> p.contains("local host"));
        assertThat(check(List.of("prod"), GOOD_SECRET, " , ", GOOD_MEDIA)).anyMatch(p -> p.contains("ALLOWED_ORIGINS"));
    }

    @Test
    @DisplayName("media base URL must be https and public")
    void badMediaUrl_refused() {
        assertThat(check(List.of("prod"), GOOD_SECRET, GOOD_ORIGINS, "http://localhost:8081/uploads")).anyMatch(p -> p.startsWith("MEDIA_BASE_URL"));
        assertThat(check(List.of("prod"), GOOD_SECRET, GOOD_ORIGINS, "")).anyMatch(p -> p.contains("missing"));
    }

    // ---------------------------------------------------------------- wiring

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProductionProfileGuard.class)
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"));

    @Test
    @DisplayName("the prod context does not start with a development configuration")
    void prodContext_badConfig_failsToStart() {
        runner.withPropertyValues("jwt.secret=short", "allowed.origins=http://localhost:5173",
                        "media.base-url=http://localhost:8081/uploads")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("Production configuration refused");
                });
    }

    @Test
    @DisplayName("the prod context starts with a proper configuration")
    void prodContext_goodConfig_starts() {
        runner.withPropertyValues("jwt.secret=" + GOOD_SECRET, "allowed.origins=" + GOOD_ORIGINS, "media.base-url=" + GOOD_MEDIA)
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ProductionProfileGuard.class));
    }

    @Test
    @DisplayName("the guard is not active outside the prod profile")
    void guardOnlyInProd() {
        new ApplicationContextRunner().withUserConfiguration(ProductionProfileGuard.class)
                .withPropertyValues("jwt.secret=short", "allowed.origins=*", "media.base-url=x")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ProductionProfileGuard.class));
    }

    // ---------------------------------------------------------------- the profile file itself

    @Test
    @DisplayName("application-prod.properties disables API docs, lowers log levels and takes origins/media URL from env")
    void prodPropertiesFile() throws Exception {
        Properties prod = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("logging.level.com.example.autoauction.auth")).isEqualTo("INFO");
        assertThat(prod.getProperty("logging.level.com.example.autoauction.auction")).isEqualTo("INFO");
        assertThat(prod.getProperty("logging.level.org.springframework.web.socket")).isEqualTo("INFO");
        assertThat(prod.getProperty("allowed.origins")).isEqualTo("${ALLOWED_ORIGINS}");
        assertThat(prod.getProperty("media.base-url")).isEqualTo("${MEDIA_BASE_URL}");
        assertThat(prod.stringPropertyNames()).noneMatch(k -> k.contains("password") || k.equals("jwt.secret"));
    }
}
