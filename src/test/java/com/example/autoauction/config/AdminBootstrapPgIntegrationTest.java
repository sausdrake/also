package com.example.autoauction.config;

import com.example.autoauction.user.application.AdminBootstrapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADM-01 on a fresh PostgreSQL with the prod profile: the bootstrap variables create exactly one
 * administrator who can log in; running the bootstrap again changes nothing; no development seed
 * account exists.
 */
@SpringBootTest(properties = {
        "jwt.secret=boot-test-only-secret-0123456789abcdef0123456789",
        "ALLOWED_ORIGINS=https://also.example", "MEDIA_BASE_URL=https://also.example/uploads",
        "DB_PASSWORD=unused", "R2_ACCOUNT_ID=t", "R2_BUCKET=t", "R2_ACCESS_KEY_ID=t", "R2_SECRET_ACCESS_KEY=t",
        "ALSO_ADMIN_BOOTSTRAP=true", "ALSO_ADMIN_USERNAME=ops.admin", "ALSO_ADMIN_EMAIL=ops@also.example",
        "ALSO_ADMIN_PASSWORD=Adm01-boot-pass-xyz"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
// Skipped (not failed) where no Docker daemon is reachable, e.g. the network-less verification
// container; runs wherever Testcontainers can start PostgreSQL.
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ADM-01: first admin bootstrap on a fresh production database")
class AdminBootstrapPgIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminBootstrapRunner runner;
    @Autowired private AdminBootstrapService service;
    @Autowired private MockMvc mockMvc;

    private List<String> admins() {
        return jdbc.queryForList("SELECT u.username FROM users u JOIN user_roles ur ON ur.user_id = u.id "
                + "JOIN roles r ON r.id = ur.role_id WHERE r.name = 'ROLE_ADMIN' AND u.active", String.class);
    }

    @Test
    void exactlyOneAdmin_idempotent_canLogIn_noDevSeed() throws Exception {
        assertThat(admins()).containsExactly("ops.admin");

        ApplicationArguments args = new DefaultApplicationArguments();
        runner.run(args);   // a forgotten ALSO_ADMIN_BOOTSTRAP on the next start
        assertThat(service.bootstrap("second.admin", "second@also.example", "Another-strong-pass"))
                .isEqualTo(AdminBootstrapService.Outcome.SKIPPED_ADMIN_EXISTS);
        assertThat(admins()).containsExactly("ops.admin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username IN ('admin', 'diagnostic', 'user')", Integer.class)).isZero();

        int login = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"ops.admin\",\"password\":\"Adm01-boot-pass-xyz\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(login).isEqualTo(200);

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'ops.admin'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("Adm01-boot-pass-xyz");
    }
}
