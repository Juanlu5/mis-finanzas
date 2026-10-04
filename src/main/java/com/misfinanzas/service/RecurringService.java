package com.misfinanzas.service;

import com.misfinanzas.entity.RecurringHidden;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.RecurringHiddenRepository;
import com.misfinanzas.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fixed expenses and subscriptions (the "Gastos fijos" view), found in the movements themselves: payments to the
 * same payee (or, when the description names nobody — a Bizum without concept, a cash withdrawal — of about the
 * same amount in the same category) that come back every month, two months, three months or year. Nothing is
 * stored but the groups the user hid ({@link RecurringHidden}); see docs/recurring.md for the thresholds.
 */
@Service
public class RecurringService {

    /** How far back movements are looked at. */
    static final int MONTHS_BACK = 24;
    /** Two amounts are the same price when they differ by at most this much (1 %). */
    private static final BigDecimal SAME_PRICE = new BigDecimal("0.01");

    public enum Cadence {
        MONTHLY(20, 40, 1, 30), BIMONTHLY(50, 70, 2, 61), QUARTERLY(80, 100, 3, 91), YEARLY(340, 390, 12, 365);

        final int minDays, maxDays, months, days;

        Cadence(int minDays, int maxDays, int months, int days) {
            this.minDays = minDays;
            this.maxDays = maxDays;
            this.months = months;
            this.days = days;
        }

        static Cadence of(double medianDays) {
            return Arrays.stream(values()).filter(c -> medianDays >= c.minDays && medianDays <= c.maxDays).findFirst().orElse(null);
        }
    }

    public record Charge(String date, BigDecimal amount, String description) {
    }

    /** A price that changed and has stayed since: {@code from} → {@code to} on {@code date}. */
    public record PriceChange(BigDecimal from, BigDecimal to, String date) {
    }

    /**
     * One regular payment. {@code amount} is the current price when {@code fixed}, else the usual one (median)
     * with {@code min}/{@code max}; {@code monthly} is what it costs per month. {@code grouped_by} "payee" or
     * "amount" (same category and amount, whatever the description says).
     */
    public record Item(String key, String name, String category, String grouped_by, String cadence, boolean fixed,
                       BigDecimal amount, BigDecimal min, BigDecimal max, BigDecimal monthly, String last_date,
                       String next_date, boolean active, boolean hidden, PriceChange price_change, List<Charge> charges) {
    }

    /**
     * {@code monthly_total}: the active, not hidden ones per month; {@code income_monthly}: what came in per month
     * over the last three whole months, to compare with (null without data).
     */
    public record Report(BigDecimal monthly_total, BigDecimal yearly_total, BigDecimal income_monthly, List<Item> items) {
    }

    private final TransactionRepository transactions;
    private final RecurringHiddenRepository hidden;
    private Clock clock = Clock.system(ZoneId.of("Europe/Madrid"));

    public RecurringService(TransactionRepository transactions, RecurringHiddenRepository hidden) {
        this.transactions = transactions;
        this.hidden = hidden;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public Report report() {
        LocalDate today = LocalDate.now(clock);
        List<Transaction> recent = transactions.findByDateGreaterThanEqual(today.minusMonths(MONTHS_BACK));
        Set<String> hiddenKeys = hidden.findAll().stream().map(RecurringHidden::getKey).collect(Collectors.toSet());

        List<Item> items = detect(recent, today).stream()
                .map(item -> hiddenKeys.contains(item.key()) ? withHidden(item) : item)
                .sorted(Comparator.comparing((Item i) -> !i.active())
                        .thenComparing(Item::monthly, Comparator.reverseOrder()))
                .toList();
        BigDecimal monthly = items.stream().filter(i -> i.active() && !i.hidden()).map(Item::monthly)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Report(monthly, monthly.multiply(BigDecimal.valueOf(12)), incomePerMonth(recent, today), items);
    }

    public void hide(String key) {
        RecurringHidden row = new RecurringHidden();
        row.setKey(key);
        row.setHiddenAt(Instant.now(clock));
        hidden.save(row);
    }

    public void unhide(String key) {
        hidden.deleteById(key);
    }

    private static Item withHidden(Item i) {
        return new Item(i.key(), i.name(), i.category(), i.grouped_by(), i.cadence(), i.fixed(), i.amount(), i.min(),
                i.max(), i.monthly(), i.last_date(), i.next_date(), i.active(), true, i.price_change(), i.charges());
    }

    // What came in over the last three whole months (the current one is still going), per month.
    private static BigDecimal incomePerMonth(List<Transaction> recent, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        Set<YearMonth> months = Set.of(current.minusMonths(1), current.minusMonths(2), current.minusMonths(3));
        List<Transaction> income = recent.stream()
                .filter(t -> t.getAmount().signum() > 0 && months.contains(YearMonth.from(t.getDate()))).toList();
        if (income.isEmpty()) return null;
        BigDecimal total = income.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
    }

    // ---------- detection ----------

    private record Payment(LocalDate date, BigDecimal amount, String description, String category) {
    }

    /** The regular payments among {@code movements} (expenses only), active or not, none hidden. */
    static List<Item> detect(List<Transaction> movements, LocalDate today) {
        Map<String, List<Payment>> byPayee = new LinkedHashMap<>();
        Map<String, List<Payment>> anonymousByCategory = new LinkedHashMap<>();
        for (Transaction t : movements) {
            if (t.getAmount().signum() >= 0 || t.getDate().isAfter(today)) continue;
            Payment p = new Payment(t.getDate(), t.getAmount().negate(), t.getDescription(), t.getCategory());
            String payee = Payee.keyOf(t.getDescription());
            if (payee.isEmpty()) anonymousByCategory.computeIfAbsent(t.getCategory(), k -> new ArrayList<>()).add(p);
            else byPayee.computeIfAbsent(payee, k -> new ArrayList<>()).add(p);
        }

        List<Item> items = new ArrayList<>();
        byPayee.forEach((payee, payments) -> {
            List<Payment> sorted = byDate(payments);
            Payment last = sorted.getLast();
            evaluate("comercio:" + payee, Payee.nameOf(last.description()), last.category(), "payee", sorted, today)
                    .ifPresent(items::add);
        });
        anonymousByCategory.forEach((category, payments) -> {
            for (List<Payment> cluster : amountClusters(payments)) {
                BigDecimal usual = median(cluster.stream().map(Payment::amount).toList());
                String key = "importe:" + category + ":" + usual.setScale(0, RoundingMode.HALF_UP).toPlainString();
                evaluate(key, category, category, "amount", byDate(cluster), today).ifPresent(items::add);
            }
        });
        return items;
    }

    private static List<Payment> byDate(List<Payment> payments) {
        return payments.stream().sorted(Comparator.comparing(Payment::date)).toList();
    }

    // Amounts within 10 % of the smallest of their group go together: 400 and 421 of rent, not 50 and 200.
    private static List<List<Payment>> amountClusters(List<Payment> payments) {
        List<Payment> byAmount = payments.stream().sorted(Comparator.comparing(Payment::amount)).toList();
        List<List<Payment>> clusters = new ArrayList<>();
        for (Payment p : byAmount) {
            List<Payment> open = clusters.isEmpty() ? null : clusters.getLast();
            if (open != null && p.amount().compareTo(open.getFirst().amount().multiply(new BigDecimal("1.10"))) <= 0) open.add(p);
            else clusters.add(new ArrayList<>(List.of(p)));
        }
        return clusters;
    }

    private static Optional<Item> evaluate(String key, String name, String category, String groupedBy,
                                                     List<Payment> payments, LocalDate today) {
        int n = payments.size();
        if (n < 2) return Optional.empty();
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < n; i++) gaps.add(ChronoUnit.DAYS.between(payments.get(i - 1).date(), payments.get(i).date()));
        Cadence cadence = Cadence.of(medianOf(gaps));
        if (cadence == null) return Optional.empty();
        double regular = gaps.stream().filter(g -> g >= cadence.minDays && g <= cadence.maxDays).count() / (double) gaps.size();

        List<BigDecimal> amounts = payments.stream().map(Payment::amount).toList();
        long samePrice = 0;
        for (int i = 1; i < n; i++) if (samePrice(amounts.get(i - 1), amounts.get(i))) samePrice++;
        boolean fixed = samePrice / (double) (n - 1) >= 0.6;
        List<BigDecimal> latest = amounts.subList(Math.max(0, n - 12), n);
        BigDecimal usual = median(latest);

        boolean recurring;
        if (fixed) {
            recurring = n >= (cadence == Cadence.YEARLY ? 2 : 3) && regular >= 0.6;
        } else {
            // A bill that changes every time (electricity) needs more evidence than a fixed price does
            BigDecimal most = usual.multiply(new BigDecimal("1.75")), least = usual.multiply(new BigDecimal("0.25"));
            recurring = cadence != Cadence.YEARLY && n >= 6 && regular >= 0.75
                    && latest.stream().allMatch(a -> a.compareTo(most) <= 0 && a.compareTo(least) >= 0);
        }
        if (!recurring) return Optional.empty();

        Payment last = payments.getLast();
        BigDecimal amount = fixed ? last.amount() : usual;
        boolean active = ChronoUnit.DAYS.between(last.date(), today) <= Math.round(cadence.days * 1.5) + 5;
        List<Charge> charges = payments.reversed().stream().limit(12)
                .map(p -> new Charge(p.date().toString(), p.amount(), p.description())).toList();
        return Optional.of(new Item(key, name, category, groupedBy, cadence.name().toLowerCase(), fixed, amount,
                latest.stream().min(Comparator.naturalOrder()).orElseThrow(),
                latest.stream().max(Comparator.naturalOrder()).orElseThrow(),
                amount.divide(BigDecimal.valueOf(cadence.months), 2, RoundingMode.HALF_UP),
                last.date().toString(), last.date().plusMonths(cadence.months).toString(), active, false,
                fixed ? priceChange(payments, today) : null, charges));
    }

    // The newest change of price that has held since, from a price paid at least twice before it; within a year.
    private static PriceChange priceChange(List<Payment> payments, LocalDate today) {
        int i = payments.size() - 1;
        while (i > 0 && samePrice(payments.get(i - 1).amount(), payments.get(i).amount())) i--;
        if (i < 2 || !samePrice(payments.get(i - 2).amount(), payments.get(i - 1).amount())) return null;
        Payment first = payments.get(i);
        if (first.date().isBefore(today.minusYears(1))) return null;
        return new PriceChange(payments.get(i - 1).amount(), first.amount(), first.date().toString());
    }

    private static boolean samePrice(BigDecimal a, BigDecimal b) {
        BigDecimal tolerance = b.multiply(SAME_PRICE).max(new BigDecimal("0.01"));
        return a.subtract(b).abs().compareTo(tolerance) <= 0;
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid)
                : sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static double medianOf(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /**
     * Who a description says was paid, the way the rule suggestion reads it (suggestKeyword in
     * views/transactions.js): a card payment's merchant, a Bizum's concept, whatever a transfer or a direct debit
     * names. Empty when it names nobody: a Bizum "Sin concepto", a transfer that only carries a reference, a cash
     * withdrawal.
     */
    static final class Payee {
        private Payee() {
        }

        static String keyOf(String description) {
            return nameOf(description).toLowerCase();
        }

        static String nameOf(String description) {
            String[] parts = description.split(" — ", 2);
            String head = parts[0], tail = parts.length > 1 ? parts[1] : "";
            if (tail.isEmpty() || tail.equalsIgnoreCase("pago con tarjeta")) return clean(head);
            if (head.equalsIgnoreCase("bizum")) {
                String concept = tail.replaceFirst("^[^:]*:\\s*", "");
                return concept.equalsIgnoreCase("sin concepto") ? "" : clean(concept);
            }
            return clean(tail); // "" when the detail is only a reference: the operation alone names nobody
        }

        // Drops what changes from one payment to the next: what follows a "*" and words with two digits or more.
        private static String clean(String text) {
            return Arrays.stream(text.split("\\*", 2)[0].trim().split("\\s+"))
                    .filter(word -> word.chars().filter(Character::isDigit).count() < 2)
                    .collect(Collectors.joining(" "))
                    .replaceAll("[\\s.,;:—-]+$", "")
                    .trim();
        }
    }
}
