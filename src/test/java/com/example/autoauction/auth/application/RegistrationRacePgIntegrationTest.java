package com.example.autoauction.auth.application;

import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-AUTH-01 on real PostgreSQL: the per-JVM username lock in AuthService.register does not cover
 * the email, so concurrent registrations of one email under different usernames reach the INSERT
 * and only the V1 users_email_key constraint decides. The losers must get the domain conflict
 * (reason EMAIL_TAKEN, public 409 REGISTRATION_CONFLICT), never the generic "Failed to create
 * user account" (500), and exactly one
 * account may exist. The constraint names asserted here are the PostgreSQL defaults that
 * AuthService.conflictingField relies on.
 */
@DisplayName("BE-AUTH-01: concurrent registration race resolved by the users unique constraints (PG)")
class RegistrationRacePgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("the users unique constraints carry the names conflictingField matches")
    void constraintNames() {
        List<String> names = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'users'::regclass AND contype = 'u'", String.class);
        assertThat(names).contains("users_username_key", "users_email_key");
    }

    @Test
    @DisplayName("same email, different usernames, in parallel -> one account, the rest EMAIL_TAKEN")
    void sameEmailRace() throws Exception {
        String email = "race" + System.nanoTime() + "@test.com";
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String username = "racer" + System.nanoTime() + "x" + i;
                Callable<Object> task = () -> {
                    start.await();
                    try {
                        return authService.register(new AuthService.RegisterRequest(username, email, "password123"));
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            int ok = 0;
            for (Future<Object> f : results) {
                Object r = f.get(60, TimeUnit.SECONDS);
                if (r instanceof AuthService.AuthResponse) {
                    ok++;
                } else {
                    assertThat(r).isInstanceOf(RegistrationConflictException.class);
                    assertThat(((RegistrationConflictException) r).getReasonCode()).isEqualTo("EMAIL_TAKEN");
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("same username in sequence -> USERNAME_TAKEN, one account")
    void sameUsername() {
        String username = "solo" + System.nanoTime();
        authService.register(new AuthService.RegisterRequest(username, username + "@test.com", "password123"));

        RegistrationConflictException e = org.junit.jupiter.api.Assertions.assertThrows(RegistrationConflictException.class,
                () -> authService.register(new AuthService.RegisterRequest(username, "x" + username + "@test.com", "password123")));
        assertThat(e.getReasonCode()).isEqualTo("USERNAME_TAKEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username)).isEqualTo(1);
    }
}
