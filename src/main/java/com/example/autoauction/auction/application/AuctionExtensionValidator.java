// auction/application/AuctionExtensionValidator.java
package com.example.autoauction.auction.application;

import com.example.autoauction.config.AuctionExtensionProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuctionExtensionValidator {

    private final AuctionExtensionProperties properties;

    public AuctionExtensionValidator(AuctionExtensionProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        log.info("=== Auction Extension Configuration ===");
        log.info("Enabled: {}", properties.isEnabled());
        log.info("Threshold seconds: {}", properties.getThresholdSeconds());
        log.info("Extension minutes: {}", properties.getExtensionMinutes());
        log.info("Max extensions: {}", properties.getMaxExtensions() == 0 ? "unlimited" : properties.getMaxExtensions());
        log.info("=======================================");

        if (properties.isEnabled()) {
            if (properties.getThresholdSeconds() <= 0) {
                log.warn("Threshold seconds is <= 0, auctions may never extend!");
            }
            if (properties.getExtensionMinutes() <= 0) {
                log.warn("Extension minutes is <= 0, auction will not actually extend!");
            }
        }
    }
}