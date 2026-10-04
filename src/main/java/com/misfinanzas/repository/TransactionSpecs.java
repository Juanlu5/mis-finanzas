package com.misfinanzas.repository;

import com.misfinanzas.entity.Transaction;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Arrays;

public final class TransactionSpecs {

    private TransactionSpecs() {
    }

    /**
     * Specification.allOf(...) requires every element to be non-null in this
     * Spring Data version (it throws "Other specification must not be null"
     * otherwise), so filter out the null/"no filter" specs ourselves.
     */
    @SafeVarargs
    public static Specification<Transaction> combine(Specification<Transaction>... specs) {
        return Arrays.stream(specs)
                .filter(java.util.Objects::nonNull)
                .reduce(Specification::and)
                .orElse((root, query, cb) -> cb.conjunction());
    }

    public static Specification<Transaction> monthEquals(String month) {
        return (root, query, cb) -> month == null ? null : cb.equal(root.get("accountingMonth"), month);
    }

    /** Accounting months {@code from} to {@code to} ("YYYY-MM", inclusive; stored as text, which sorts as dates). */
    public static Specification<Transaction> accountingMonthBetween(String from, String to) {
        return (root, query, cb) -> from == null || to == null ? null : cb.between(root.get("accountingMonth"), from, to);
    }

    public static Specification<Transaction> categoryEquals(String category) {
        return (root, query, cb) -> category == null ? null : cb.equal(root.get("category"), category);
    }

    public static Specification<Transaction> accountEquals(String account) {
        return (root, query, cb) -> account == null ? null : cb.equal(root.get("account"), account);
    }

    /** "expense" = negative amounts, "income" = positive ones, null = both. */
    public static Specification<Transaction> kindEquals(String kind) {
        return (root, query, cb) -> kind == null ? null
                : kind.equals("income") ? cb.greaterThan(root.get("amount"), BigDecimal.ZERO)
                : cb.lessThan(root.get("amount"), BigDecimal.ZERO);
    }

    /**
     * Size of the amount, whatever its sign, from {@code min} to {@code max} euros (inclusive; either may be null).
     * Half a cent of slack on each end: the comparison runs in SQLite, where amounts are REALs (see Money), and
     * one stored by arithmetic on doubles before amounts were exact may sit a hair off its cents.
     */
    public static Specification<Transaction> amountBetween(BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            if (min == null && max == null) return null;
            var size = cb.abs(root.<BigDecimal>get("amount"));
            if (max == null) return cb.greaterThanOrEqualTo(size, min.subtract(HALF_CENT));
            if (min == null) return cb.lessThanOrEqualTo(size, max.add(HALF_CENT));
            return cb.between(size, min.subtract(HALF_CENT), max.add(HALF_CENT));
        };
    }

    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

    public static Specification<Transaction> descriptionContains(String search) {
        return (root, query, cb) -> search == null ? null
                : cb.like(cb.lower(root.get("description")), "%" + search.toLowerCase() + "%");
    }
}
