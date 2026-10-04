package com.misfinanzas.startup;

import com.misfinanzas.dto.ImportRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The demo's bank movements: two years of an invented person's account, up to today. Each month is drawn from
 * a random generator seeded with that month, so a month always comes out the same, whatever day the demo starts.
 * Descriptions have the shape the bank download stores ({@code "Mercadona sants — Pago con tarjeta"}, see
 * EnableBankingClient.cleanDescription), so the default rules and {@link DemoDataRunner#DEMO_RULES} categorize
 * most of them; a few (Bizums, a bar, Bonpreu) are left "Sin categorizar" on purpose, to try the rules on.
 */
final class DemoTransactions {

    static final String ACCOUNT = "Principal";
    static final int MONTHS = 24;
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("6420.00");

    private record Movement(LocalDate date, String description, BigDecimal amount) {
    }

    private DemoTransactions() {
    }

    /** Oldest first, each with the balance after it; nothing after {@code today}. */
    static List<ImportRow> build(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        List<Movement> movements = new ArrayList<>();
        for (YearMonth month = current.minusMonths(MONTHS - 1); !month.isAfter(current); month = month.plusMonths(1)) {
            new MonthBuilder(month, movements).build();
        }
        movements.removeIf(m -> m.date().isAfter(today));
        movements.sort(Comparator.comparing(Movement::date)); // stable: same-day movements keep their order

        List<ImportRow> rows = new ArrayList<>(movements.size());
        BigDecimal balance = OPENING_BALANCE;
        for (Movement m : movements) {
            balance = balance.add(m.amount());
            rows.add(new ImportRow(m.date(), m.description(), m.amount(), ACCOUNT, balance));
        }
        return rows;
    }

    private static final class MonthBuilder {
        private static final String CARD = " — Pago con tarjeta";
        private static final String[] SUPERMARKETS = {"Mercadona sants", "Mercadona sants", "Mercadona sants", "Lidl badal",
                "Lidl badal", "Carrefour market", "Dia sants", "Dia sants", "Bonpreu"};
        private static final String[] RESTAURANTS = {"Restaurante la tagliatella", "Cafeteria el mirador", "Glovo", "Glovo",
                "Restaurante can pau", "Cafeteria el mirador", "Bar el tomate"};
        private static final String[] BIZUM_SENT = {"Cena cumple", "Regalo compartido", "Entradas concierto", "Loteria",
                "Sin concepto"};
        private static final String[] BIZUM_RECEIVED = {"Cena viernes", "Parte del regalo", "Taxi aeropuerto"};

        private final YearMonth month;
        private final List<Movement> out;
        private final Random random;

        MonthBuilder(YearMonth month, List<Movement> out) {
            this.month = month;
            this.out = out;
            this.random = new Random(month.getYear() * 100L + month.getMonthValue());
        }

        void build() {
            int m = month.getMonthValue();
            BigDecimal salary = yearly("2150.00", "0.03");
            add(lastWorkingDay(), "Ingreso por nómina o pensión — Soluciones norte sl", salary);
            if (m == 6 || m == 12) add(workingDayFrom(20), "Ingreso por nómina o pensión — Soluciones norte sl", salary);

            add(workingDayFrom(1), "Transferencia realizada — Alquiler piso", yearly("875.00", "0.02").negate());
            add(workingDayFrom(2), "Transferencia realizada — Aportacion fondo indexado", new BigDecimal("-200.00"));
            add(workingDayFrom(6), "Adeudo de recibo — Endesa energia sau", between(electricityLow(m), electricityLow(m) + 25).negate());
            add(workingDayFrom(8), "Adeudo de recibo — Digi spain telecom", new BigDecimal("-30.00"));
            add(workingDayFrom(3), "Adeudo de recibo — Gimnasio fitpark", new BigDecimal("-39.90"));
            // Netflix went up in April 2026; HBO Max was dropped after January 2026 (the Gastos fijos view shows both)
            add(day(12), "Netflix.com" + CARD, new BigDecimal(month.isBefore(YearMonth.of(2026, 4)) ? "-12.99" : "-13.99"));
            if (!month.isBefore(YearMonth.of(2025, 2)) && !month.isAfter(YearMonth.of(2026, 1))) {
                add(day(16), "Hbo max" + CARD, new BigDecimal("-9.99"));
            }
            add(day(20), "Spotify" + CARD, new BigDecimal("-11.99"));
            add(day(1 + random.nextInt(5)), "Renfe viajeros" + CARD, new BigDecimal("-21.35"));

            repeat(7, 10, () -> add(anyDay(), pick(SUPERMARKETS) + CARD, between(12, 85).negate()));
            repeat(3, 6, () -> add(anyDay(), pick(RESTAURANTS) + CARD, between(9, 45).negate()));
            repeat(0, 2, () -> add(anyDay(), "Cabify" + CARD, between(9, 19).negate()));
            repeat(0, 2, () -> add(anyDay(), "Farmacia sant antoni" + CARD, between(6, 25).negate()));
            repeat(0, 3, () -> add(anyDay(), "Www.amazon*" + orderId() + CARD, between(12, 60).negate()));
            repeat(0, 1, () -> add(anyDay(), "Cines verdi" + CARD, between(8, 20).negate()));
            repeat(1, 3, () -> add(anyDay(), "Bizum — ENVIADO: " + pick(BIZUM_SENT), between(8, 35).negate()));
            repeat(0, 2, () -> add(anyDay(), "Bizum — RECIBIDO: " + pick(BIZUM_RECEIVED), between(10, 40)));
            repeat(0, 1, () -> add(anyDay(), "Ret. efectivo a debito con tarj. en cajero. aut. — 01820002 999",
                    BigDecimal.valueOf(new int[]{20, 40, 50}[random.nextInt(3)] * 100L, 2).negate()));

            // What makes one month unlike another
            if (m == 1 || m == 7) add(day(8), "Zara" + CARD, between(45, 120).negate()); // sales
            if (m == 3) add(workingDayFrom(5), "Adeudo de recibo — Mapfre seguros hogar", new BigDecimal("-186.40"));
            if (m == 6) add(workingDayFrom(24), "Transferencia recibida — Devolucion aeat irpf", between(380, 520));
            if (m == 7) add(day(4), "Booking.com" + CARD, between(420, 620).negate());
            if (m == 8) {
                add(day(3), "Renfe viajeros" + CARD, between(80, 110).negate());
                repeat(4, 6, () -> add(day(3 + random.nextInt(10)), pick(RESTAURANTS) + CARD, between(20, 60).negate()));
            }
            if (m == 11) add(day(27), "Mediamarkt" + CARD, between(250, 400).negate()); // Black Friday
            if (m == 12) {
                add(day(18), "El corte ingles" + CARD, between(150, 250).negate());
                add(day(21), "Www.amazon*" + orderId() + CARD, between(40, 90).negate());
            }
        }

        private void add(LocalDate date, String description, BigDecimal amount) {
            out.add(new Movement(date, description, amount));
        }

        private void repeat(int min, int max, Runnable action) {
            int times = min + random.nextInt(max - min + 1);
            for (int i = 0; i < times; i++) action.run();
        }

        /** From 2025's value, up by {@code rise} each later year: a pay rise, the rent's update. */
        private BigDecimal yearly(String in2025, String rise) {
            BigDecimal factor = BigDecimal.ONE.add(new BigDecimal(rise)).pow(Math.abs(month.getYear() - 2025));
            BigDecimal value = new BigDecimal(in2025);
            return month.getYear() < 2025 ? value.divide(factor, 2, RoundingMode.HALF_UP)
                    : value.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }

        /** Heating in winter and air conditioning in summer. */
        private static int electricityLow(int m) {
            return switch (m) {
                case 12, 1, 2 -> 70;
                case 7, 8 -> 60;
                default -> 40;
            };
        }

        private BigDecimal between(int min, int max) {
            int cents = min * 100 + random.nextInt((max - min) * 100 + 1);
            return BigDecimal.valueOf(cents, 2);
        }

        private String pick(String[] options) {
            return options[random.nextInt(options.length)];
        }

        private String orderId() {
            String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 8; i++) id.append(chars.charAt(random.nextInt(chars.length())));
            return id.toString();
        }

        private LocalDate day(int dayOfMonth) {
            return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
        }

        private LocalDate anyDay() {
            return day(1 + random.nextInt(month.lengthOfMonth()));
        }

        private LocalDate workingDayFrom(int dayOfMonth) {
            LocalDate date = day(dayOfMonth);
            while (isWeekend(date)) date = date.plusDays(1);
            return date;
        }

        private LocalDate lastWorkingDay() {
            LocalDate date = month.atEndOfMonth();
            while (isWeekend(date)) date = date.minusDays(1);
            return date;
        }

        private static boolean isWeekend(LocalDate date) {
            return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        }
    }
}
