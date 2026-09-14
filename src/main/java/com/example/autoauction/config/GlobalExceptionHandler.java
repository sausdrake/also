// Обнови файл: config/GlobalExceptionHandler.java

package com.example.autoauction.config;

import com.example.autoauction.auction.application.RateLimitException;
import com.example.autoauction.deposit.domain.InsufficientFundsException;
import com.example.autoauction.policy.application.PolicyConflictException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.FieldError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Некорректный формат параметра: {} со значением {}", e.getName(), e.getValue());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.BAD_REQUEST.value());
        error.put("error", "Некорректный формат параметра");
        error.put("parameter", e.getName());
        error.put("value", String.valueOf(e.getValue()));
        error.put("message", "Ожидался числовой идентификатор");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    // ← НОВЫЙ ОБРАБОТЧИК: Ошибки аутентификации
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException e) {
        log.warn("Authentication failed: {}", e.getMessage());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.UNAUTHORIZED.value());
        error.put("error", "Unauthorized");
        error.put("message", "Invalid username or password");

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    // ← НОВЫЙ ОБРАБОТЧИК: Ошибки доступа
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.FORBIDDEN.value());
        error.put("error", "Forbidden");
        error.put("message", "You don't have permission to access this resource");

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    // ← НОВЫЙ ОБРАБОТЧИК: Конфликт данных (уникальные ограничения)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMessage());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.CONFLICT.value());
        error.put("error", "Data conflict");

        // Извлекаем более понятное сообщение
        String message = "Database constraint violation";
        if (e.getMessage() != null && e.getMessage().toLowerCase().contains("idempotency_key")) {
            message = "Эта корректировка уже была обработана ранее (повтор с тем же ключом идемпотентности)";
        } else if (e.getMessage().contains("UK_")) {
            if (e.getMessage().contains("username")) {
                message = "Username already exists";
            } else if (e.getMessage().contains("email")) {
                message = "Email already exists";
            } else if (e.getMessage().contains("vin")) {
                message = "Vehicle with this VIN already exists";
            }
        }
        error.put("message", message);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    // Policy Administration concurrency lock: one structured conflict family, one stable `code`
    // field per concrete subtype - the smallest consistent extension to this project's existing
    // {timestamp, status, error, message} error body, given no pre-existing stable error-code
    // convention exists. Mapped to 409, the same established status every other business-state
    // conflict in this handler already uses.
    @ExceptionHandler(PolicyConflictException.class)
    public ResponseEntity<Map<String, Object>> handlePolicyConflict(PolicyConflictException e) {
        log.warn("Policy conflict [{}]: {}", e.getCode(), e.getMessage());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.CONFLICT.value());
        error.put("error", "Конфликт состояния");
        error.put("code", e.getCode());
        error.put("message", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    // Policy Administration concurrency lock: Hibernate's own @Version flush-time check (the
    // genuine-race half of Boundary A, distinct from PolicyConflictException's explicit
    // up-front staleness pre-check - see OptimisticVersionConflictException's Javadoc). Same
    // stable code, for a frontend that only cares about the category, not which half caught it.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException e) {
        log.warn("Optimistic locking conflict: {}", e.getMessage());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.CONFLICT.value());
        error.put("error", "Конфликт состояния");
        error.put("code", "OPTIMISTIC_VERSION_CONFLICT");
        error.put("message", "Данные были изменены другим администратором. Обновите данные и попробуйте снова.");

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        String message = e.getMessage();
        HttpStatus status = HttpStatus.BAD_REQUEST;

        if (message != null && message.contains("не найден")) {
            status = HttpStatus.NOT_FOUND;
        }

        log.warn("Некорректный аргумент: {} -> статус {}", message, status);

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", status.value());
        error.put("error", status == HttpStatus.NOT_FOUND ? "Ресурс не найден" : "Некорректный запрос");
        error.put("message", message);

        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("Некорректное состояние: {}", e.getMessage());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.CONFLICT.value());
        error.put("error", "Конфликт состояния");
        error.put("message", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(InsufficientFundsException e) {
        log.warn("Insufficient funds: available={}, required={}", e.getAvailable(), e.getRequired());
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", 422);
        error.put("error", "Insufficient Funds");
        error.put("message", e.getMessage());
        error.put("available", e.getAvailable());
        error.put("required", e.getRequired());
        return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON).body(error);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurityException(SecurityException e) {
        log.warn("Security exception: {}", e.getMessage());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.FORBIDDEN.value());
        error.put("error", "Forbidden");
        error.put("message", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException e) {
        log.warn("Ошибка валидации: {}", e.getMessage());

        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Ошибка валидации");
        response.put("details", errors);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException e) {
        log.warn("Rate limit: {}", e.getMessage());
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", 429);
        error.put("error", "Слишком много запросов");
        error.put("message", e.getMessage());
        return ResponseEntity.status(429).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("Внутренняя ошибка сервера", e);

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        error.put("error", "Внутренняя ошибка сервера");
        error.put("message", "Произошла непредвиденная ошибка");

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParams(MissingServletRequestParameterException e) {
        log.warn("Отсутствует обязательный параметр: {}", e.getParameterName());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.BAD_REQUEST.value());
        error.put("error", "Отсутствует обязательный параметр");
        error.put("message", "Параметр '" + e.getParameterName() + "' обязателен для запроса");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }
}