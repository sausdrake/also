package com.example.autoauction.admin.application;

import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

@Slf4j
@Component
public class AuctionValidator {

    private static final long MIN_AUCTION_DURATION_HOURS = 1;
    private static final long MAX_AUCTION_DURATION_DAYS = 30;
    private static final long MIN_HOURS_BEFORE_START = 1;
    private static final BigDecimal MIN_BID_STEP_PERCENTAGE = new BigDecimal("0.01");

    private final Clock clock;

    public AuctionValidator(Clock clock) {
        this.clock = clock;
    }

    public void validateBasicRequest(AuctionCreateRequest request) {
        log.debug("Базовая валидация запроса на создание аукциона");

        Objects.requireNonNull(request.vehicleId(), "ID автомобиля не может быть null");
        Objects.requireNonNull(request.startingPrice(), "Стартовая цена не может быть null");
        Objects.requireNonNull(request.minBidStep(), "Шаг ставки не может быть null");
        Objects.requireNonNull(request.startTime(), "Время начала не может быть null");
        Objects.requireNonNull(request.endTime(), "Время окончания не может быть null");
    }

    public void validateBusinessRules(AuctionCreateRequest request, VehicleResponse vehicle) {
        log.debug("Валидация бизнес-правил для аукциона");

        validatePrices(request);
        validateBidStep(request);
        validateTime(request);

        validateVehicleCompatibility(request, vehicle);
    }

    private void validatePrices(AuctionCreateRequest request) {
        if (request.startingPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    String.format("Стартовая цена должна быть больше 0. Получено: %s", request.startingPrice())
            );
        }

        if (request.reservePrice() != null) {
            if (request.reservePrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        String.format("Резервная цена должна быть больше 0. Получено: %s", request.reservePrice())
                );
            }
            if (request.reservePrice().compareTo(request.startingPrice()) < 0) {
                throw new IllegalArgumentException(
                        String.format("Резервная цена (%s) не может быть меньше стартовой (%s)",
                                request.reservePrice(), request.startingPrice())
                );
            }
        }

        if (request.buyNowPrice() != null) {
            if (request.buyNowPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        String.format("Цена мгновенной покупки должна быть больше 0. Получено: %s", request.buyNowPrice())
                );
            }
            if (request.buyNowPrice().compareTo(request.startingPrice()) <= 0) {
                throw new IllegalArgumentException(
                        String.format("Цена мгновенной покупки (%s) должна быть больше стартовой (%s)",
                                request.buyNowPrice(), request.startingPrice())
                );
            }
        }
    }

    private void validateBidStep(AuctionCreateRequest request) {
        if (request.minBidStep().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    String.format("Шаг ставки должен быть больше 0. Получено: %s", request.minBidStep())
            );
        }

        BigDecimal minAllowedStep = request.startingPrice().multiply(MIN_BID_STEP_PERCENTAGE);
        if (request.minBidStep().compareTo(minAllowedStep) < 0) {
            throw new IllegalArgumentException(
                    String.format("Шаг ставки (%s) не может быть меньше 1%% от стартовой цены (%s)",
                            request.minBidStep(), minAllowedStep)
            );
        }
    }

    private void validateTime(AuctionCreateRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (request.startTime().isBefore(now)) {
            throw new IllegalArgumentException(
                    String.format("Время начала аукциона (%s) не может быть в прошлом. Текущее время: %s",
                            request.startTime(), now)
            );
        }

        if (!request.endTime().isAfter(request.startTime())) {
            throw new IllegalArgumentException(
                    String.format("Время окончания (%s) должно быть позже времени начала (%s)",
                            request.endTime(), request.startTime())
            );
        }

        Duration duration = Duration.between(request.startTime(), request.endTime());
        long minutes = duration.toMinutes();

        if (minutes < MIN_AUCTION_DURATION_HOURS * 60) {
            throw new IllegalArgumentException(
                    String.format("Аукцион должен длиться минимум %d час(ов). Текущая длительность: %d минут",
                            MIN_AUCTION_DURATION_HOURS, minutes)
            );
        }

        if (minutes > MAX_AUCTION_DURATION_DAYS * 24 * 60) {
            throw new IllegalArgumentException(
                    String.format("Аукцион не может длиться больше %d дней. Текущая длительность: %d дней",
                            MAX_AUCTION_DURATION_DAYS, minutes / (24 * 60))
            );
        }

        Duration tillStart = Duration.between(now, request.startTime());
        if (tillStart.toHours() < MIN_HOURS_BEFORE_START) {
            throw new IllegalArgumentException(
                    String.format("Аукцион должен быть создан минимум за %d час(ов) до начала. До начала осталось: %d минут",
                            MIN_HOURS_BEFORE_START, tillStart.toMinutes())
            );
        }

        log.debug("Валидация времени успешно пройдена. Длительность аукциона: {} минут", minutes);
    }

    private void validateVehicleCompatibility(AuctionCreateRequest request, VehicleResponse vehicle) {
        log.debug("Проверка совместимости автомобиля с аукционом");
    }
}