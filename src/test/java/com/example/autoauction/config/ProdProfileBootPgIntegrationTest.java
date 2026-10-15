package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CFG-01: the application really boots with SPRING_PROFILES_ACTIVE=prod alone (no test profile)
 * on a fresh PostgreSQL: every prod placeholder resolves, the startup guard accepts a proper
 * configuration, Flyway migrates the empty database through the latest migration, the health
 * endpoint answers and the API description is offline. Placeholders are fake, non-local values.
 */
@SpringBootTest(properties = {
        "jwt.secret=boot-test-only-secret-0123456789abcdef0123456789",
        "ALLOWED_ORIGINS=https://also.example",
        "MEDIA_BASE_URL=https://also.example/uploads",
        "DB_PASSWORD=unused-service-connection-overrides-it",
        "R2_ACCOUNT_ID=boot-test", "R2_BUCKET=boot-test", "R2_ACCESS_KEY_ID=boot-test", "R2_SECRET_ACCESS_KEY=boot-test"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
// Skipped (not failed) where no Docker daemon is reachable, e.g. the network-less verification
// container; runs wherever Testcontainers can start PostgreSQL.
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("CFG-01: the prod profile boots on a fresh PostgreSQL")
class ProdProfileBootPgIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void prodProfile_boots_migrates_andHidesApiDocs() throws Exception {
        assertThat(Arrays.asList(context.getEnvironment().getActiveProfiles())).containsExactly("prod");
        assertThat(context.getBeansOfType(ProductionProfileGuard.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(DataInitializer.class)).as("dev seeding is off").isEmpty();

        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).contains("1", "55", "56", "57");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();

        assertThat(mockMvc.perform(get("/api/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get("/api-docs")).andReturn().getResponse().getStatus()).isNotEqualTo(200);
        assertThat(mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus()).isNotEqualTo(200);
        assertThat(context.getEnvironment().getProperty("logging.level.com.example.autoauction.auth")).isEqualTo("INFO");
    }
}
