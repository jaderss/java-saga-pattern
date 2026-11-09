package dev.jaderss.saga.messaging.contract;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException(
                    "amount %s has more decimals than %s allows".formatted(amount, currency));
        }
        amount = amount.setScale(currency.getDefaultFractionDigits());
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currency));
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
