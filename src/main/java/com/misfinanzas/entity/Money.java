package com.misfinanzas.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amounts of money are {@link BigDecimal}s with two decimals (cents) everywhere in the code, so sums and
 * differences are exact: a {@code double} can't hold most cent amounts (0.29 is really 0.28999...) and the
 * error adds up over a sum.
 *
 * <p>SQLite has no exact decimal type, so the columns stay REAL (as they always were: no data migration) and
 * {@link Converter} rounds to cents on the way in and out. A REAL holds any two-decimal amount below ~10^13
 * closely enough that rounding it back gives exactly the same cents. On Postgres the column would be a
 * {@code numeric(19,2)} and the converter could go.
 */
public final class Money {

    public static final int SCALE = 2;

    private Money() {
    }

    /** {@code amount} rounded to cents (half up), or null. */
    public static BigDecimal of(BigDecimal amount) {
        return amount == null ? null : amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** The amount in whole cents: 45.30 -> 4530. */
    public static long cents(BigDecimal amount) {
        return of(amount).unscaledValue().longValueExact();
    }

    @Converter
    public static class Column implements AttributeConverter<BigDecimal, Double> {

        @Override
        public Double convertToDatabaseColumn(BigDecimal amount) {
            return amount == null ? null : amount.doubleValue();
        }

        @Override
        public BigDecimal convertToEntityAttribute(Double stored) {
            // valueOf goes through the shortest decimal that reads back as this double: 0.28999... -> 0.29
            return stored == null ? null : of(BigDecimal.valueOf(stored));
        }
    }
}
