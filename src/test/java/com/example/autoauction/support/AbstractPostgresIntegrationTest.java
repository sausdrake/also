package com.example.autoauction.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Slice 2 (Bank / Payment Infrastructure): base class for financial money-path integration tests
 * that MUST run against a real PostgreSQL - partial unique indexes, {@code SELECT ... FOR UPDATE}
 * blocking, transaction isolation, {@code NUMERIC(19,2)} precision and true concurrent-webhook
 * races cannot be faithfully exercised on H2.
 *
 * <p>TEST-01: the container is a JVM-wide singleton - started once in the static initializer and
 * stopped by Testcontainers' reaper when the JVM exits. Managed by the JUnit extension
 * ({@code @Container}) it was stopped after every test class while Spring kept the cached context
 * pointing at it, so the next class sharing that context failed to connect: the PG classes could
 * only be run one at a time. All subclasses share one database; each test creates its own data.
 * {@code disabledWithoutDocker} on a subclass is evaluated before the class is initialized, so
 * without Docker the class is still skipped, not failed. As before, the container is reused
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
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
