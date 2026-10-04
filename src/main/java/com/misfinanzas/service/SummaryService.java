package com.misfinanzas.service;

import com.misfinanzas.entity.Money;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.TransactionRepository;
import com.misfinanzas.repository.TransactionSpecs;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static com.misfinanzas.repository.TransactionSpecs.combine;
import static com.misfinanzas.repository.TransactionSpecs.monthEquals;

@Service
public class SummaryService {

    private final TransactionRepository transactionRepository;

    public SummaryService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public record Summary(BigDecimal income, BigDecimal expense, BigDecimal balance, long count) {
    }

    public Summary summary(String month) {
        return summary(month, null);
    }

    /**
     * {@code until} (or null) leaves out movements dated after it: a month still in progress is compared with
     * the previous one up to the same day, not with all of it (see the dashboard's previousPeriod).
     */
    public Summary summary(String month, LocalDate until) {
        return summary(combine(monthEquals(month), datedUpTo(until)));
    }

    /** Totals over the accounting months {@code from} to {@code to}, inclusive. */
    public Summary summary(YearMonth from, YearMonth to) {
        return summary(from, to, null);
    }

    public Summary summary(YearMonth from, YearMonth to, LocalDate until) {
        return summary(combine(accountingMonthBetween(from, to), datedUpTo(until)));
    }

    private static Specification<Transaction> datedUpTo(LocalDate until) {
        return until == null ? null : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("date"), until);
    }

    private Summary summary(Specification<Transaction> spec) {
        List<Transaction> txs = transactionRepository.findAll(spec);
        BigDecimal income = sum(txs.stream().filter(t -> t.getAmount().signum() > 0).map(Transaction::getAmount).toList());
        BigDecimal expense = sum(txs.stream().filter(t -> t.getAmount().signum() < 0).map(t -> t.getAmount().negate()).toList());
        return new Summary(income, expense, Money.of(income.subtract(expense)), txs.size());
    }

    public record MonthlyTotal(String month, BigDecimal income, BigDecimal expense) {
    }

    /** Income and expense of one month while it's being added up. */
    private static final class Bucket {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;

        void add(BigDecimal amount) {
            if (amount.signum() > 0) income = income.add(amount);
            else expense = expense.add(amount.negate());
        }

        MonthlyTotal total(String month) {
            return new MonthlyTotal(month, Money.of(income), Money.of(expense));
        }
    }

    public List<MonthlyTotal> summaryMonthly(int months) {
        Map<String, Bucket> buckets = new HashMap<>();
        for (Transaction t : transactionRepository.findAll()) {
            String key = t.getAccountingMonth();
            if (key == null) continue;
            buckets.computeIfAbsent(key, k -> new Bucket()).add(t.getAmount());
        }
        return buckets.keySet().stream()
                .sorted()
                .skip(Math.max(0, buckets.size() - months))
                .map(key -> buckets.get(key).total(key))
                .toList();
    }

    /**
     * Income and expense of every accounting month from {@code from} to {@code to} (inclusive), months without
     * movements included as zeros: a chart over a period should show a quiet month as a gap, not skip it.
     */
    public List<MonthlyTotal> summaryMonthly(YearMonth from, YearMonth to) {
        Map<String, Bucket> buckets = new LinkedHashMap<>(); // in month order
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) buckets.put(m.toString(), new Bucket());
        for (Transaction t : transactionRepository.findAll(accountingMonthBetween(from, to))) {
            Bucket bucket = buckets.get(t.getAccountingMonth());
            if (bucket == null) continue; // not a well-formed YYYY-MM, though it sorted inside the range
            bucket.add(t.getAmount());
        }
        return buckets.entrySet().stream()
                .map(e -> e.getValue().total(e.getKey()))
                .toList();
    }

    public record BalancePoint(String date, BigDecimal balance) {
    }

    public record AccountBalance(String account, BigDecimal balance, String date) {
    }

    /**
     * The money there is now, as far as the stored movements say: per account, the balance after its newest
     * movement that has one, and their sum. {@code date} is the oldest of those, so "as of" never claims to be
     * fresher than the stalest account. Null total and date when no movement has a balance.
     */
    public record CurrentBalance(BigDecimal total, String date, List<AccountBalance> accounts) {
    }

    public CurrentBalance currentBalance() {
        Map<String, List<Transaction>> byAccount = new TreeMap<>();
        for (Transaction t : transactionRepository.findAll((root, query, cb) -> cb.isNotNull(root.get("balance")))) {
            byAccount.computeIfAbsent(String.valueOf(t.getAccount()), a -> new ArrayList<>()).add(t);
        }
        List<AccountBalance> accounts = new ArrayList<>();
        byAccount.forEach((account, txs) -> {
            LocalDate newest = txs.stream().map(Transaction::getDate).max(Comparator.naturalOrder()).orElseThrow();
            Transaction last = lastOfTheDay(txs.stream().filter(t -> t.getDate().equals(newest)).toList());
            accounts.add(new AccountBalance(account, last.getBalance(), newest.toString()));
        });
        if (accounts.isEmpty()) return new CurrentBalance(null, null, List.of());
        BigDecimal total = sum(accounts.stream().map(AccountBalance::balance).toList());
        String date = accounts.stream().map(AccountBalance::date).min(Comparator.naturalOrder()).orElseThrow();
        return new CurrentBalance(total, date, accounts);
    }

    public List<BalancePoint> balanceHistory(String account) {
        return balanceHistory(account, null, null);
    }

    /**
     * The account balance at the end of each day with movements between {@code from} and {@code to} (either may
     * be null for no limit): one point per day (and account), so a long period stays readable and a day with several movements
     * doesn't draw a zigzag inside it.
     */
    public List<BalancePoint> balanceHistory(String account, LocalDate from, LocalDate to) {
        Specification<Transaction> spec = combine(
                (root, query, cb) -> cb.isNotNull(root.get("balance")),
                account == null ? null : (root, query, cb) -> cb.equal(root.get("account"), account),
                from == null ? null : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("date"), from),
                to == null ? null : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("date"), to));
        // Per day and account: two accounts' balances on the same day are two points, never one.
        Map<LocalDate, Map<String, List<Transaction>>> byDay = new TreeMap<>();
        for (Transaction t : transactionRepository.findAll(spec)) {
            byDay.computeIfAbsent(t.getDate(), d -> new TreeMap<>())
                    .computeIfAbsent(String.valueOf(t.getAccount()), a -> new ArrayList<>()).add(t);
        }
        List<BalancePoint> points = new ArrayList<>();
        byDay.forEach((date, accounts) -> accounts.values().forEach(day ->
                points.add(new BalancePoint(date.toString(), lastOfTheDay(day).getBalance()))));
        return points;
    }

    /**
     * Which of one day's movements came last. Their ids don't say (a bank download stores them in the bank's
     * order, which for BBVA is newest first), but their balances do: each movement starts from the previous
     * one's balance (its own balance minus its amount), so the last one is the only balance no other movement
     * starts from. If the balances don't chain that way (a gap, or rows from different sources), the most
     * recently stored one is taken, as before.
     */
    static Transaction lastOfTheDay(List<Transaction> day) {
        if (day.size() == 1) return day.get(0);
        Set<Long> startsInCents = new HashSet<>();
        for (Transaction t : day) startsInCents.add(Money.cents(t.getBalance().subtract(t.getAmount())));
        List<Transaction> candidates = day.stream()
                .filter(t -> !startsInCents.contains(Money.cents(t.getBalance())))
                .toList();
        List<Transaction> pool = candidates.size() == 1 ? candidates : day;
        return pool.stream().max(Comparator.comparing(Transaction::getId)).orElseThrow();
    }

    public record CategoryTotal(String category, BigDecimal amount) {
    }

    public List<CategoryTotal> summaryByCategory(String month) {
        return summaryByCategory(monthEquals(month));
    }

    /** Expenses per category over the accounting months {@code from} to {@code to}, inclusive. */
    public List<CategoryTotal> summaryByCategory(YearMonth from, YearMonth to) {
        return summaryByCategory(from, to, null);
    }

    /** The same, leaving out movements dated after {@code until} (or null): see {@link #summary(String, LocalDate)}. */
    public List<CategoryTotal> summaryByCategory(YearMonth from, YearMonth to, LocalDate until) {
        return summaryByCategory(combine(accountingMonthBetween(from, to), datedUpTo(until)));
    }

    private List<CategoryTotal> summaryByCategory(Specification<Transaction> period) {
        Specification<Transaction> spec = combine(
                TransactionSpecs.kindEquals("expense"),
                period);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Transaction t : transactionRepository.findAll(spec)) {
            totals.merge(t.getCategory(), t.getAmount().negate(), BigDecimal::add);
        }
        return totals.entrySet().stream()
                .map(e -> new CategoryTotal(e.getKey(), Money.of(e.getValue())))
                .sorted(Comparator.comparing(CategoryTotal::amount).reversed())
                .toList();
    }

    private static Specification<Transaction> accountingMonthBetween(YearMonth from, YearMonth to) {
        return TransactionSpecs.accountingMonthBetween(from.toString(), to.toString());
    }

    public List<String> monthsAvailable() {
        return transactionRepository.findDistinctAccountingMonths();
    }

    /** Exact sum, in cents. */
    private static BigDecimal sum(List<BigDecimal> amounts) {
        return Money.of(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
