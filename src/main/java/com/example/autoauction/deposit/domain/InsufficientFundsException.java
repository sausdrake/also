package com.example.autoauction.deposit.domain;

public class InsufficientFundsException extends RuntimeException {
    private final java.math.BigDecimal available;
    private final java.math.BigDecimal required;

    public InsufficientFundsException(java.math.BigDecimal available, java.math.BigDecimal required) {
        super(String.format("Недостаточно средств: доступно %s, требуется %s", available, required));
        this.available = available;
        this.required = required;
    }

    public java.math.BigDecimal getAvailable() { return available; }
    public java.math.BigDecimal getRequired() { return required; }
}
