package com.example.autoauction.admin.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты SystemSettingsService")
class SystemSettingsServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private SystemSettingsService service;

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("Возвращает значение настройки")
        void get_ReturnsValue() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("deposit_percent")))
                    .thenReturn("1");

            String result = service.get("deposit_percent");

            assertThat(result).isEqualTo("1");
        }

        @Test
        @DisplayName("Возвращает null если настройка не найдена")
        void get_ReturnsNullWhenNotFound() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("missing_key")))
                    .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

            String result = service.get("missing_key");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getInt()")
    class GetIntTests {

        @Test
        @DisplayName("Возвращает целое число")
        void getInt_ReturnsInteger() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("auction_confirm_hours")))
                    .thenReturn("2");

            int result = service.getInt("auction_confirm_hours", 3);

            assertThat(result).isEqualTo(2);
        }

        @Test
        @DisplayName("Возвращает defaultValue если ключ отсутствует")
        void getInt_ReturnsDefaultOnNull() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("unknown")))
                    .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

            int result = service.getInt("unknown", 5);

            assertThat(result).isEqualTo(5);
        }

        @Test
        @DisplayName("Возвращает defaultValue если значение не число")
        void getInt_ReturnsDefaultOnBadValue() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("bad")))
                    .thenReturn("not_a_number");

            int result = service.getInt("bad", 10);

            assertThat(result).isEqualTo(10);
        }
    }

    // PHASE 2 (fail-closed resolution): getRequiredBigDecimal must never substitute anything -
    // not a hardcoded fallback, not zero, not another setting's value - for a missing,
    // malformed, or negative value. Every failure path throws IllegalStateException.
    @Nested
    @DisplayName("getRequiredBigDecimal()")
    class GetRequiredBigDecimalTests {

        @Test
        @DisplayName("Возвращает настроенное значение")
        void getRequiredBigDecimal_ReturnsConfiguredValue() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("diagnostic_fee_amount")))
                    .thenReturn("4000");

            java.math.BigDecimal result = service.getRequiredBigDecimal("diagnostic_fee_amount");

            assertThat(result).isEqualByComparingTo("4000");
        }

        @Test
        @DisplayName("Отсутствующая настройка - IllegalStateException, без подстановки значения")
        void getRequiredBigDecimal_Missing_ThrowsIllegalStateException() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("diagnostic_fee_amount")))
                    .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

            assertThatThrownBy(() -> service.getRequiredBigDecimal("diagnostic_fee_amount"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("diagnostic_fee_amount");
        }

        @Test
        @DisplayName("Нечисловое значение - IllegalStateException")
        void getRequiredBigDecimal_Malformed_ThrowsIllegalStateException() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("diagnostic_fee_amount")))
                    .thenReturn("not_a_number");

            assertThatThrownBy(() -> service.getRequiredBigDecimal("diagnostic_fee_amount"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("diagnostic_fee_amount");
        }

        @Test
        @DisplayName("Отрицательное значение - IllegalStateException")
        void getRequiredBigDecimal_Negative_ThrowsIllegalStateException() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("diagnostic_fee_amount")))
                    .thenReturn("-1000");

            assertThatThrownBy(() -> service.getRequiredBigDecimal("diagnostic_fee_amount"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("diagnostic_fee_amount");
        }

        @Test
        @DisplayName("Нулевое значение допустимо (не отрицательное)")
        void getRequiredBigDecimal_Zero_IsAccepted() {
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("diagnostic_fee_amount")))
                    .thenReturn("0");

            java.math.BigDecimal result = service.getRequiredBigDecimal("diagnostic_fee_amount");

            assertThat(result).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("set()")
    class SetTests {

        @Test
        @DisplayName("Обновляет существующее значение через UPDATE")
        void set_UpdatesExistingKey() {
            when(jdbc.update(contains("UPDATE"), anyString(), eq("deposit_percent"))).thenReturn(1);

            service.set("deposit_percent", "2");

            verify(jdbc).update(contains("UPDATE"), eq("2"), eq("deposit_percent"));
        }

        @Test
        @DisplayName("Вставляет разрешённое значение через INSERT если UPDATE не нашёл строку")
        void set_InsertsAllowedKey() {
            when(jdbc.update(contains("UPDATE"), anyString(), anyString())).thenReturn(0);

            service.set("deposit_percent", "2");

            verify(jdbc).update(contains("INSERT"), eq("deposit_percent"), eq("2"));
        }
    }

    @Nested
    @DisplayName("setAll()")
    class SetAllTests {

        @Test
        @DisplayName("Обновляет несколько разрешённых настроек")
        void setAll_UpdatesAllowedKeys() {
            when(jdbc.update(contains("UPDATE"), anyString(), anyString())).thenReturn(1);

            service.setAll(Map.of("deposit_percent", "2", "platform_fee_percent", "5"));

            verify(jdbc, times(2)).update(contains("UPDATE"), anyString(), anyString());
        }

        @Test
        @DisplayName("Отклоняет setAll с хотя бы одним недопустимым ключом — fail-fast")
        void setAll_WithOneInvalidKey_RejectsAll() {
            Map<String, String> mixed = new java.util.LinkedHashMap<>();
            mixed.put("deposit_percent", "2");
            mixed.put("evil_key", "DROP TABLE users");

            assertThatThrownBy(() -> service.setAll(mixed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evil_key");

            // Ничего не должно быть записано — ни один UPDATE/INSERT
            verify(jdbc, never()).update(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Whitelist ключей — защита от произвольных записей")
    class KeyWhitelistTests {

        @Test
        @DisplayName("Разрешённые ключи проходят валидацию")
        void set_AllowedKeys_AreAccepted() {
            when(jdbc.update(contains("UPDATE"), anyString(), anyString())).thenReturn(1);

            for (String key : SystemSettingsService.ALLOWED_KEYS) {
                assertThatCode(() -> service.set(key, "1"))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Произвольный ключ отклоняется с IllegalArgumentException")
        void set_ArbitraryKey_Rejected() {
            assertThatThrownBy(() -> service.set("unknown_key", "value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown_key");
        }

        @Test
        @DisplayName("Пустой ключ отклоняется")
        void set_EmptyKey_Rejected() {
            assertThatThrownBy(() -> service.set("", "value"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("SQL-инъекция в ключе отклоняется whitelist'ом")
        void set_SqlInjectionInKey_Rejected() {
            String maliciousKey = "deposit_percent; DROP TABLE system_settings; --";
            assertThatThrownBy(() -> service.set(maliciousKey, "1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Ключ с символами верхнего регистра отклоняется (case-sensitive)")
        void set_UpperCaseKey_Rejected() {
            assertThatThrownBy(() -> service.set("DEPOSIT_PERCENT", "1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
