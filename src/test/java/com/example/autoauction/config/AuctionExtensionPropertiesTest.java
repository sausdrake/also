// config/AuctionExtensionPropertiesTest.java
package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Тесты конфигурации продления аукциона")
class AuctionExtensionPropertiesTest {

    @Autowired
    private AuctionExtensionProperties properties;

    @Test
    @DisplayName("Конфигурация загружается с правильными значениями по умолчанию")
    void propertiesShouldHaveDefaultValues() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getThresholdSeconds()).isEqualTo(300); // application.properties, da70a77
        assertThat(properties.getExtensionMinutes()).isEqualTo(5);
        assertThat(properties.getMaxExtensions()).isEqualTo(0);
    }
}