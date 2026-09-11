package com.example.autoauction.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Slice 2 (Bank / Payment Infrastructure): base class for financial money-path integration tests
 * that MUST run against a real PostgreSQL - partial unique indexes, {@code SELECT ... FOR UPDATE}
 * blocking, transaction isolation, {@code NUMERIC(19,2)} precision and true concurrent-webhook
 * races cannot be faithfully exercised on H2.
 *
 * <p>The container is {@code static} so Testcontainers starts it once per JVM fork and reuses it
 * across every subclass; {@link ServiceConnection} (Spring Boot 3.1+) wires the application
 * datasource to it automatically - no {@code @DynamicPropertySource} plumbing.
 *
 * <p>Profiles: {@code "test"} keeps the existing test doubles in effect (FakeStorageClient,
 * FakeVideoProcessor); {@code "pg"} (loaded last) flips only the database layer to real
 * PostgreSQL with Flyway V1..V42. The resulting Spring context is a distinct cache key from the
 * H2 {@code "test"} context, so the existing H2 suite is untouched.
 *
 * <p>Pinned image: {@code postgres:16-alpine} (never {@code postgres:latest}).
 */
@SpringBootTest
@ActiveProfiles({"test", "pg"})
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
}
