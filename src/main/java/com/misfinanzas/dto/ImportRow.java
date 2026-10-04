package com.misfinanzas.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * @param alternativeDescriptions other descriptions this row would have been stored under by an earlier
 *        import with a different choice of the optional "detail" column, keyed by a name for that choice
 *        (see {@code ImportService}). Only used to recognise rows that were already imported.
 */
public record ImportRow(LocalDate date, String description, BigDecimal amount, String account, BigDecimal balance,
                        Map<String, String> alternativeDescriptions) {

    public ImportRow(LocalDate date, String description, BigDecimal amount, String account, BigDecimal balance) {
        this(date, description, amount, account, balance, Map.of());
    }
}
