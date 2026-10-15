package com.example.autoauction.auction.web.validation;

import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AuctionPricesValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static AuctionCreateRequest request(BigDecimal startingPrice, BigDecimal reservePrice,
                                                BigDecimal buyNowPrice) {
        OffsetDateTime start = OffsetDateTime.parse("2030-01-01T10:00:00+00:00");
        return new AuctionCreateRequest(1L, startingPrice, reservePrice, buyNowPrice,
                new BigDecimal("10"), start, start.plusDays(7), "https://cdn.test/main.jpg", null);
    }

    private static Set<String> properties(Set<ConstraintViolation<AuctionCreateRequest>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void startingPriceNullWithReserve_returnsViolationsWithoutException() {
        AuctionCreateRequest request = request(null, new BigDecimal("100"), null);

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
        assertThat(properties(validator.validate(request))).contains("startingPrice");
    }

    @Test
    void startingPriceNullWithBuyNow_returnsViolationsWithoutException() {
        AuctionCreateRequest request = request(null, null, new BigDecimal("100"));

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
        assertThat(properties(validator.validate(request))).contains("startingPrice");
    }

    @Test
    void startingPriceNullOnly_yieldsExactlyStartingPriceViolation() {
        Set<ConstraintViolation<AuctionCreateRequest>> violations =
                validator.validate(request(null, null, null));

        assertThat(violations).hasSize(1);
        ConstraintViolation<AuctionCreateRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("startingPrice");
        assertThat(violation.getMessage()).isEqualTo("Стартовая цена обязательна");
    }

    @Test
    void reserveBelowStarting_violationOnReservePrice() {
        Set<ConstraintViolation<AuctionCreateRequest>> violations =
                validator.validate(request(new BigDecimal("100"), new BigDecimal("99"), null));

        assertThat(violations).hasSize(1);
        ConstraintViolation<AuctionCreateRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("reservePrice");
        assertThat(violation.getMessage())
                .isEqualTo("Резервная цена (99) не может быть меньше стартовой (100)");
    }

    @Test
    void reserveEqualsStarting_valid() {
        assertThat(validator.validate(request(new BigDecimal("100"), new BigDecimal("100"), null)))
                .isEmpty();
    }

    @Test
    void buyNowEqualsStarting_violationOnBuyNowPrice() {
        Set<ConstraintViolation<AuctionCreateRequest>> violations =
                validator.validate(request(new BigDecimal("100"), null, new BigDecimal("100")));

        assertThat(violations).hasSize(1);
        ConstraintViolation<AuctionCreateRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("buyNowPrice");
        assertThat(violation.getMessage())
                .isEqualTo("Цена мгновенной покупки (100) должна быть больше стартовой (100)");
    }

    @Test
    void buyNowAboveStarting_valid() {
        assertThat(validator.validate(request(new BigDecimal("100"), null, new BigDecimal("101"))))
                .isEmpty();
    }

    @Test
    void startingPriceNullWithReserveAndBuyNow_onlyStartingPriceViolation() {
        Set<ConstraintViolation<AuctionCreateRequest>> violations =
                validator.validate(request(null, new BigDecimal("100"), new BigDecimal("50")));

        assertThat(properties(violations)).containsExactly("startingPrice");
    }

    @Test
    void buyNowBelowStarting_violationOnBuyNowPrice() {
        Set<ConstraintViolation<AuctionCreateRequest>> violations =
                validator.validate(request(new BigDecimal("100"), null, new BigDecimal("99")));

        assertThat(violations).hasSize(1);
        ConstraintViolation<AuctionCreateRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("buyNowPrice");
        assertThat(violation.getMessage())
                .isEqualTo("Цена мгновенной покупки (99) должна быть больше стартовой (100)");
    }

    @Test
    void reserveAndBuyNowBothInvalid_bothViolationsReported() {
        Set<ConstraintViolation<AuctionCreateRequest>> violations =
                validator.validate(request(new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("100")));

        assertThat(properties(violations)).containsExactlyInAnyOrder("reservePrice", "buyNowPrice");
    }

    @Test
    void differentScaleEqualValues_comparedNumerically() {
        assertThat(validator.validate(request(new BigDecimal("100"), new BigDecimal("100.00"), null)))
                .isEmpty();
        assertThat(properties(validator.validate(request(new BigDecimal("100"), null, new BigDecimal("100.00")))))
                .containsExactly("buyNowPrice");
    }

    @Test
    void bothOptionalPricesNull_valid() {
        assertThat(validator.validate(request(new BigDecimal("100"), null, null))).isEmpty();
    }
}
