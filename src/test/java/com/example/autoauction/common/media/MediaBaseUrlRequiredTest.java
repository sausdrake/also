package com.example.autoauction.common.media;

import com.example.autoauction.common.media.application.MediaAssetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DEV-02: media.base-url is required. MediaController used to fall back to
 * http://localhost:8080/uploads - a port nothing listens on - when the property was missing. Every
 * profile sets it, and the other beans that read it have no fallback, so the fallback could only
 * have turned a missing setting into silently wrong public URLs. Now a missing value is a start-up
 * error, the same contract as LegacyMediaMigrationService and ProductionProfileGuard.
 */
@DisplayName("DEV-02: media.base-url is required, no hidden fallback")
class MediaBaseUrlRequiredTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withBean(MediaAssetService.class, () -> mock(MediaAssetService.class))
            .withBean(MediaController.class);

    @Test
    @DisplayName("without media.base-url the controller does not start - no silent localhost URL")
    void missingProperty_failsStartUp() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("media.base-url");
        });
    }

    @Test
    @DisplayName("with media.base-url the controller uses exactly that value")
    void configuredProperty_isUsed() {
        runner.withPropertyValues("media.base-url=https://media.example.test/uploads").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(MediaController.class), "baseUrl"))
                    .isEqualTo("https://media.example.test/uploads");
        });
    }
}
