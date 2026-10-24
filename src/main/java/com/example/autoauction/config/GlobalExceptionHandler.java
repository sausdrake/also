// Обнови файл: config/GlobalExceptionHandler.java

package com.example.autoauction.config;

import com.example.autoauction.auction.application.RateLimitException;
import com.example.autoauction.auction.application.TrustedParticipantRequiredException;
import com.example.autoauction.auth.application.RegistrationConflictException;
import com.example.autoauction.auth.application.TelegramAuthException;
import com.example.autoauction.deposit.domain.InsufficientFundsException;
import com.example.autoauction.identity.application.IdentityVerificationRequiredException;
import com.example.autoauction.legal.application.LegalAcceptanceRequiredException;
import com.example.autoauction.policy.application.PolicyConflictException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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
        if (e.getMessage() != null && e.getMessage().toLowerCase().contains("uk_deposit_tx_reconciliation_reference")) {
            message = "Эта внешняя операция уже зачислена (повтор по той же ссылке RECONCILIATION)";
        } else if (e.getMessage() != null && e.getMessage().toLowerCase().contains("idempotency_key")) {
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

    // MVP-004: KYC gate refusal - 403 with a stable machine-readable code so clients can route
    // the user to identity verification instead of showing a generic "forbidden".
    @ExceptionHandler(LegalAcceptanceRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleLegalAcceptanceRequired(LegalAcceptanceRequiredException e) {
        log.warn("Legal acceptance required: {}", e.getCode());
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.FORBIDDEN.value());
        error.put("error", "Forbidden");
        error.put("code", e.getCode());
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(IdentityVerificationRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleIdentityVerificationRequired(IdentityVerificationRequiredException e) {
        log.warn("Identity verification required: {}", e.getCode());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.FORBIDDEN.value());
        error.put("error", "Forbidden");
        error.put("code", e.getCode());
        error.put("message", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    // AUTH-TELEGRAM-01: a Telegram sign-in / link refusal - its own safe code and message, never the provider's text
    @ExceptionHandler(TelegramAuthException.class)
    public ResponseEntity<Map<String, Object>> handleTelegramAuth(TelegramAuthException e) {
        log.warn("Telegram auth refused: {}", e.getReason().code());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", e.getReason().status().value());
        error.put("error", e.getReason().status().getReasonPhrase());
        error.put("code", e.getReason().code());
        error.put("message", e.getMessage());

        return ResponseEntity
                .status(e.getReason().status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    // KYC-01: trust gate refusal (bid / Buy Now) - 403 with its own code, so a user whose identity is
    // already verified is told about the trusted status instead of being sent to verify again.
    @ExceptionHandler(TrustedParticipantRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleTrustedParticipantRequired(TrustedParticipantRequiredException e) {
        log.warn("Trusted participant required: {}", e.getCode());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.FORBIDDEN.value());
        error.put("error", "Forbidden");
        error.put("code", e.getCode());
        error.put("message", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    // BE-AUTH-01: duplicate username/email at registration - 409 instead of the generic 500 a bare
    // RuntimeException used to produce. Anti-enumeration: one public code and message for both a
    // taken username and a taken email; the conflicting field is logged server-side only.
    @ExceptionHandler(RegistrationConflictException.class)
    public ResponseEntity<Map<String, Object>> handleRegistrationConflict(RegistrationConflictException e) {
        log.warn("Registration conflict: {}", e.getReasonCode());

        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", java.time.LocalDateTime.now().toString());
        error.put("status", HttpStatus.CONFLICT.value());
        error.put("error", "Conflict");
        error.put("code", RegistrationConflictException.PUBLIC_CODE);
        error.put("message", RegistrationConflictException.PUBLIC_MESSAGE);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
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

    // ERR-01: an unreadable body (malformed JSON, empty body, unknown field, unknown enum value,
    // wrong JSON type) is the client's mistake - 400, not a 500 "internal error". The parser's
    // message names classes, fields and input fragments, so it is logged, never returned.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Некорректное тело запроса: {}", e.getMostSpecificCause().getMessage());
        return clientError(HttpStatus.BAD_REQUEST, "Некорректное тело запроса",
                "Тело запроса не удалось прочитать: проверьте формат JSON, имена и значения полей");
    }

    // MEDIA-PROD-01: a multipart file over spring.servlet.multipart.max-file-size - 413, not 500.
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("Upload too large: {}", e.getMaxUploadSize());
        return clientError(HttpStatus.PAYLOAD_TOO_LARGE, "Файл слишком большой", "Файл слишком большой: максимум 10 МБ");
    }

    // A multipart endpoint called without a multipart body (no file part) - 400, not 500. The more specific
    // MaxUploadSizeExceededException handler above still answers 413.
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(org.springframework.web.multipart.MultipartException e) {
        log.warn("Malformed multipart request: {}", e.getClass().getSimpleName());
        return clientError(HttpStatus.BAD_REQUEST, "Некорректный запрос", "Ожидается загрузка файла (multipart/form-data)");
    }

    // ERR-01: wrong Content-Type - 415, not 500.
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("Неподдерживаемый Content-Type: {}", e.getContentType());
        return clientError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый тип содержимого",
                "Неподдерживаемый тип содержимого запроса");
    }

    // ERR-01: wrong HTTP method - 405, not 500.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Неподдерживаемый HTTP-метод: {}", e.getMethod());
        return clientError(HttpStatus.METHOD_NOT_ALLOWED, "Метод не поддерживается",
                "HTTP-метод " + e.getMethod() + " не поддерживается для этого адреса");
    }

    // SEC-15: since Spring 6.1 an unknown path raises NoResourceFoundException; without this handler
    // the generic Exception handler answered 500 for every mistyped or removed route.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        log.warn("Не найдено: {} {}", e.getHttpMethod(), e.getResourcePath());
        return clientError(HttpStatus.NOT_FOUND, "Не найдено", "Запрошенный адрес не существует");
    }

    private static ResponseEntity<Map<String, Object>> clientError(HttpStatus status, String error, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", java.time.LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
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