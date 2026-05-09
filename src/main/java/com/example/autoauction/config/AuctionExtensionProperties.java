// config/AuctionExtensionProperties.java
package com.example.autoauction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "auction.extension")
public class AuctionExtensionProperties {

    /**
     * Включено ли автопродление аукциона
     * default: true
     */
    private boolean enabled = true;

    /**
     * Порог в секундах до окончания, при котором происходит продление
     * default: 30 секунд
     */
    private int thresholdSeconds = 30;

    /**
     * На сколько минут продлевать аукцион при каждой поздней ставке
     * default: 5 минут
     */
    private int extensionMinutes = 5;

    /**
     * Максимальное количество продлений (0 = безлимитно)
     * default: 0 (безлимитно)
     */
    private int maxExtensions = 0;

    /**
     * Минимальное время аукциона в минутах
     * default: 1 минута
     */
    private int minAuctionDurationMinutes = 1;

    /**
     * Максимальное время аукциона в днях
     * default: 30 дней
     */
    private int maxAuctionDurationDays = 30;

    /**
     * Минимальное время до начала аукциона в часах
     * default: 1 час
     */
    private int minHoursBeforeStart = 1;

    /**
     * Минимальный шаг ставки в процентах от стартовой цены
     * default: 1%
     */
    private double minBidStepPercentage = 1.0;

    // Геттеры и сеттеры
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getThresholdSeconds() {
        return thresholdSeconds;
    }

    public void setThresholdSeconds(int thresholdSeconds) {
        this.thresholdSeconds = thresholdSeconds;
    }

    public int getExtensionMinutes() {
        return extensionMinutes;
    }

    public void setExtensionMinutes(int extensionMinutes) {
        this.extensionMinutes = extensionMinutes;
    }

    public int getMaxExtensions() {
        return maxExtensions;
    }

    public void setMaxExtensions(int maxExtensions) {
        this.maxExtensions = maxExtensions;
    }

    public int getMinAuctionDurationMinutes() {
        return minAuctionDurationMinutes;
    }

    public void setMinAuctionDurationMinutes(int minAuctionDurationMinutes) {
        this.minAuctionDurationMinutes = minAuctionDurationMinutes;
    }

    public int getMaxAuctionDurationDays() {
        return maxAuctionDurationDays;
    }

    public void setMaxAuctionDurationDays(int maxAuctionDurationDays) {
        this.maxAuctionDurationDays = maxAuctionDurationDays;
    }

    public int getMinHoursBeforeStart() {
        return minHoursBeforeStart;
    }

    public void setMinHoursBeforeStart(int minHoursBeforeStart) {
        this.minHoursBeforeStart = minHoursBeforeStart;
    }

    public double getMinBidStepPercentage() {
        return minBidStepPercentage;
    }

    public void setMinBidStepPercentage(double minBidStepPercentage) {
        this.minBidStepPercentage = minBidStepPercentage;
    }

    @Override
    public String toString() {
        return String.format(
                "AuctionExtensionProperties{enabled=%s, thresholdSeconds=%d, extensionMinutes=%d, maxExtensions=%d}",
                enabled, thresholdSeconds, extensionMinutes, maxExtensions
        );
    }
}