package com.misfinanzas;

import com.misfinanzas.entity.Money;

import java.math.BigDecimal;

/** Amounts for tests, written as plain numbers: {@code eur(-45.3)} is -45.30 with two decimals, as stored. */
public final class TestMoney {

    private TestMoney() {
    }

    public static BigDecimal eur(double amount) {
        return Money.of(BigDecimal.valueOf(amount));
    }

    /** Null stays null (a balance that isn't there). */
    public static BigDecimal eur(Double amount) {
        return amount == null ? null : eur(amount.doubleValue());
    }
}
