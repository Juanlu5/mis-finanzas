package com.misfinanzas.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    private final Money.Column column = new Money.Column();

    @Test
    void roundsToCentsHalfUp() {
        assertThat(Money.of(new BigDecimal("45.3"))).isEqualTo(new BigDecimal("45.30"));
        assertThat(Money.of(new BigDecimal("0.125"))).isEqualTo(new BigDecimal("0.13"));
        assertThat(Money.of(new BigDecimal("-0.125"))).isEqualTo(new BigDecimal("-0.13"));
        assertThat(Money.of(null)).isNull();
    }

    @Test
    void centsAreWholeNumbers() {
        assertThat(Money.cents(new BigDecimal("45.30"))).isEqualTo(4530);
        assertThat(Money.cents(new BigDecimal("-0.29"))).isEqualTo(-29);
        assertThat(Money.cents(new BigDecimal("1700"))).isEqualTo(170000);
    }

    @Test
    void anAmountReadBackFromTheRealColumnIsExactlyTheSameCents() {
        // 0.29 can't be a double: it's stored as 0.28999999999999998, which must come back as 0.29, not 0.28
        for (String amount : new String[]{"0.29", "-0.29", "45.30", "-1700.00", "0.01", "9999999999.99"}) {
            BigDecimal stored = new BigDecimal(amount);
            assertThat(column.convertToEntityAttribute(column.convertToDatabaseColumn(stored))).isEqualTo(stored);
        }
    }

    @Test
    void readsWhatDoublesLeftInTheDatabaseAsItsCents() {
        assertThat(column.convertToEntityAttribute(0.1 + 0.2)).isEqualTo(new BigDecimal("0.30"));
        assertThat(column.convertToEntityAttribute(-1700.0)).isEqualTo(new BigDecimal("-1700.00"));
    }

    @Test
    void nullStaysNull() {
        assertThat(column.convertToDatabaseColumn(null)).isNull();
        assertThat(column.convertToEntityAttribute(null)).isNull();
    }
}
