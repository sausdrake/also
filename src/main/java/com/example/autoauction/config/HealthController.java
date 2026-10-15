// Создай файл: config/HealthController.java

package com.example.autoauction.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * OBS-02: the service is UP only when it can reach its database - otherwise the container
 * healthcheck (docker-compose: wget on this endpoint) and any uptime monitor would report a
 * service that cannot serve a single request as healthy. A DOWN database answers 503; the cause
 * is logged, never returned (driver messages can carry hosts and credentials).
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@Tag(name = "Health Check", description = "Проверка состояния сервиса")
public class HealthController {

    static final int DB_CHECK_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    @Operation(summary = "Проверка статуса сервиса и доступности базы данных")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        boolean dbUp = databaseIsUp();
        Map<String, Object> status = new HashMap<>();
        status.put("status", dbUp ? "UP" : "DOWN");
        status.put("db", dbUp ? "UP" : "DOWN");
        status.put("timestamp", LocalDateTime.now().toString());
        status.put("service", "Auto Auction API");
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(status);
    }

    private boolean databaseIsUp() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(DB_CHECK_TIMEOUT_SECONDS)) {
                return true;
            }
            log.warn("Health check: database connection is not valid");
        } catch (Exception e) {
            log.warn("Health check: database unreachable: {}", e.toString());
        }
        return false;
    }
}
