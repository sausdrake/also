package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBS-02: /api/health reports the database. Unreachable or invalid connection -> 503 DOWN, the
 * driver's message (which can carry hosts and credentials) never in the body, and a borrowed
 * connection is always returned to the pool.
 */
@DisplayName("OBS-02: health reflects the database")
class HealthControllerDatabaseCheckTest {

    @Test
    @DisplayName("valid connection -> 200 UP, db UP, connection closed")
    void databaseUp_200() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(HealthController.DB_CHECK_TIMEOUT_SECONDS)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = new HealthController(dataSource).healthCheck();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "UP").containsEntry("db", "UP");
        verify(connection).close();
    }

    @Test
    @DisplayName("database unreachable -> 503 DOWN, the driver message is not returned")
    void databaseUnreachable_503() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connect to db.internal:5432 failed, password=S3cret"));

        ResponseEntity<Map<String, Object>> response = new HealthController(dataSource).healthCheck();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("status", "DOWN").containsEntry("db", "DOWN");
        assertThat(response.getBody().toString()).doesNotContain("S3cret", "db.internal", "SQLException");
    }

    @Test
    @DisplayName("connection obtained but not valid -> 503 DOWN, connection still closed")
    void connectionInvalid_503() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(HealthController.DB_CHECK_TIMEOUT_SECONDS)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = new HealthController(dataSource).healthCheck();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("status", "DOWN");
        verify(connection).close();
    }
}
