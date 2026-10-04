package com.misfinanzas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
@Getter
@Setter
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String description;

    /** Cents exact (see {@link Money}): the setter rounds to two decimals. */
    @Column(nullable = false)
    @Convert(converter = Money.Column.class)
    private BigDecimal amount;

    @Column(nullable = false)
    private String category = "Sin categorizar";

    @Column(nullable = false)
    private String account = "Principal";

    @Column(name = "dedupe_key", nullable = false, unique = true)
    private String dedupeKey;

    /**
     * The month a transaction is reported under ("YYYY-MM"). Defaults to the
     * month of {@code date} at import time but can be overridden by hand
     * (e.g. a salary payment that lands a day early/late).
     */
    @Column(name = "accounting_month")
    private String accountingMonth;

    /**
     * Account balance right after this transaction, as reported by the bank
     * export. Null when the source file doesn't include one.
     */
    @Convert(converter = Money.Column.class)
    private BigDecimal balance;

    public void setAmount(BigDecimal amount) {
        this.amount = Money.of(amount);
    }

    public void setBalance(BigDecimal balance) {
        this.balance = Money.of(balance);
    }

    public static String monthKey(LocalDate date) {
        return "%04d-%02d".formatted(date.getYear(), date.getMonthValue());
    }
}
