package com.example.autoauction.auction.web.validation;

import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

public class AuctionPricesValidator implements ConstraintValidator<ValidAuctionPrices, AuctionCreateRequest> {

    @Override
    public boolean isValid(AuctionCreateRequest request, ConstraintValidatorContext context) {
        boolean isValid = true;

        // Отключаем стандартное сообщение, будем добавлять свои
        context.disableDefaultConstraintViolation();

        // Проверка резервной цены
        if (request.reservePrice() != null && request.startingPrice() != null) {
            if (request.reservePrice().compareTo(request.startingPrice()) < 0) {
                context.buildConstraintViolationWithTemplate(
                                String.format("Резервная цена (%s) не может быть меньше стартовой (%s)",
                                        request.reservePrice(), request.startingPrice()))
                        .addPropertyNode("reservePrice")
                        .addConstraintViolation();
                isValid = false;
            }
        }

        // Проверка цены мгновенной покупки
        if (request.buyNowPrice() != null && request.startingPrice() != null) {
            if (request.buyNowPrice().compareTo(request.startingPrice()) <= 0) {
                context.buildConstraintViolationWithTemplate(
                                String.format("Цена мгновенной покупки (%s) должна быть больше стартовой (%s)",
                                        request.buyNowPrice(), request.startingPrice()))
                        .addPropertyNode("buyNowPrice")
                        .addConstraintViolation();
                isValid = false;
            }
        }

        return isValid;
    }
}