package com.example.autoauction.admin.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SystemSettingsService {

    /**
     * DEPOSIT-01 (BD-01): the one fixed participation deposit for every NEW auction, set by the
     * administrator. Snapshotted into Auction.requiredDepositAmount when an auction is created; a
     * later change never touches existing auctions or frozen deposits. No default - creation fails
     * closed until it is set.
     */
    public static final String PARTICIPATION_DEPOSIT_KEY = "auction_participation_deposit_amount";

    /**
     * Technical capacity of auctions.required_deposit_amount (NUMERIC(10,2)) - NOT a business maximum;
     * a larger value could never be stored on the auction.
     */
    static final BigDecimal PARTICIPATION_DEPOSIT_CAPACITY = new BigDecimal("99999999.99");

    public static final Set<String> ALLOWED_KEYS = Set.of(
            PARTICIPATION_DEPOSIT_KEY,
            "deposit_percent",
            "platform_fee_percent",
            "commission_percent",
            "default_bid_step_percent",
            "diagnostic_deposit_amount",
            "diagnostic_fee_amount",
            "auction_confirm_hours",
            "completed_auction_visibility_hours"
    );

    private final JdbcTemplate jdbc;

    public SystemSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(value = "systemSettings", key = "#key")
    @Transactional(readOnly = true)
    public String get(String key) {
        try {
            return jdbc.queryForObject(
                    "SELECT setting_value FROM system_settings WHERE setting_key = ?",
                    String.class, key);
        } catch (Exception e) {
            log.warn("Setting '{}' not found, returning null", key);
            return null;
        }
    }

    @Cacheable(value = "systemSettings", key = "#key")
    @Transactional(readOnly = true)
    public int getInt(String key, int defaultValue) {
        String val = get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Setting '{}' is not an integer: '{}', using default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    @Cacheable(value = "systemSettings", key = "#key")
    @Transactional(readOnly = true)
    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        String val = get(key);
        if (val == null) return defaultValue;
        try {
            return new BigDecimal(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Setting '{}' is not a valid decimal: '{}', using default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Fail-closed variant for financial settings with no safe implicit default - unlike
     * {@link #getBigDecimal}, a missing, malformed, or negative value is never silently
     * substituted with anything (not a hardcoded fallback, not zero, not another setting's
     * value). Callers must let this exception propagate and abort whatever operation depends
     * on the value, before any ledger/commitment mutation. Spring's @Cacheable does not cache
     * thrown exceptions, so a still-unconfigured setting is re-checked (and re-thrown) on
     * every call rather than being cached as a false "not set" result.
     */
    @Cacheable(value = "systemSettings", key = "#key")
    @Transactional(readOnly = true)
    public BigDecimal getRequiredBigDecimal(String key) {
        String val = get(key);
        if (val == null) {
            throw new IllegalStateException(
                    "Обязательная настройка '" + key + "' не задана администратором");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(val.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Настройка '" + key + "' имеет некорректное числовое значение: '" + val + "'");
        }
        if (parsed.signum() < 0) {
            throw new IllegalStateException(
                    "Настройка '" + key + "' не может быть отрицательной: " + parsed);
        }
        return parsed;
    }

    @CacheEvict(value = "systemSettings", key = "#key")
    @Transactional
    public void set(String key, String value) {
        if (!ALLOWED_KEYS.contains(key)) {
            throw new IllegalArgumentException("Недопустимый ключ настройки: '" + key +
                    "'. Разрешены: " + ALLOWED_KEYS);
        }
        if (PARTICIPATION_DEPOSIT_KEY.equals(key)) {
            value = validParticipationDeposit(value);
        }
        int updated = jdbc.update(
                "UPDATE system_settings SET setting_value = ?, updated_at = NOW() WHERE setting_key = ?",
                value, key);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO system_settings (setting_key, setting_value) VALUES (?, ?)",
                    key, value);
        }
        log.info("System setting '{}' updated to '{}'", key, value);
    }

    /** DEPOSIT-01: a positive amount with at most 2 decimals that fits the auction column; stored normalized. */
    static String validParticipationDeposit(String value) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Депозит для участия должен быть числом, например 10000");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Депозит для участия должен быть больше 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Депозит для участия: не больше двух знаков после запятой");
        }
        if (amount.compareTo(PARTICIPATION_DEPOSIT_CAPACITY) > 0) {
            throw new IllegalArgumentException("Депозит для участия не может быть больше " + PARTICIPATION_DEPOSIT_CAPACITY.toPlainString());
        }
        return amount.setScale(2).toPlainString();
    }

    @CacheEvict(value = "systemSettings", allEntries = true)
    @Transactional
    public void setAll(Map<String, String> settings) {
        // Проверяем все ключи до записи — fail-fast
        settings.keySet().forEach(key -> {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Недопустимый ключ настройки: '" + key + "'");
            }
        });
        settings.forEach(this::set);
    }

    @Transactional(readOnly = true)
    public Map<String, String> getAll() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM system_settings ORDER BY setting_key",
                rs -> {
                    result.put(rs.getString("setting_key"), rs.getString("setting_value"));
                });
        return result;
    }
}
