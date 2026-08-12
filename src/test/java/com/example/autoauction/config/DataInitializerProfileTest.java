package com.example.autoauction.config;

import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("DataInitializer profile guard")
class DataInitializerProfileTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("DataInitializer bean must NOT be present when dev profile is inactive")
    void dataInitializer_isAbsent_whenDevProfileNotActive() {
        assertThat(applicationContext.containsBean("dataInitializer"))
                .as("DataInitializer must be restricted to @Profile(\"dev\") — must not run in production")
                .isFalse();
    }

    @Test
    @DisplayName("No default admin account created outside dev profile")
    void adminUser_isNotCreated_outsideDevProfile() {
        assertThat(userRepository.findByUsername("admin"))
                .as("admin/admin123 must not exist in production (non-dev) context")
                .isEmpty();
    }

    @Test
    @DisplayName("No default diagnostic account created outside dev profile")
    void diagnosticUser_isNotCreated_outsideDevProfile() {
        assertThat(userRepository.findByUsername("diagnostic"))
                .as("diagnostic/diagnostic123 must not exist in production (non-dev) context")
                .isEmpty();
    }

    @Test
    @DisplayName("No default user account created outside dev profile")
    void regularUser_isNotCreated_outsideDevProfile() {
        assertThat(userRepository.findByUsername("user"))
                .as("user/user123 must not exist in production (non-dev) context")
                .isEmpty();
    }
}
