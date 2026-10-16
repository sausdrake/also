package com.example.autoauction;

import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BASE-02: the application's DataSource reaches PostgreSQL and answers a query. It used to connect to
 * localhost:5432 with DB_PASSWORD and only print what it found; it now runs against the shared PostgreSQL
 * container and asserts. (Moved out of test.java, a file name Surefire's patterns did not describe.)
 */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseConnectionTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testDatabaseConnection() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.isValid(5)).isTrue();
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }
}
