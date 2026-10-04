package com.misfinanzas.service;

import com.misfinanzas.entity.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.misfinanzas.TestMoney.eur;
import static org.assertj.core.api.Assertions.assertThat;

/** The detection on its own, over movements shaped like the real ones (bank download descriptions). */
class RecurringServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);
    private final List<Transaction> movements = new ArrayList<>();

    private void add(String date, String description, double amount, String category) {
        Transaction t = new Transaction();
        t.setDate(LocalDate.parse(date));
        t.setDescription(description);
        t.setAmount(eur(amount));
        t.setCategory(category);
        movements.add(t);
    }

    /** The same payment on day {@code day} of each month from {@code from} to {@code to} ("YYYY-MM"). */
    private void monthly(String from, String to, int day, String description, double amount, String category) {
        for (var m = java.time.YearMonth.parse(from); !m.isAfter(java.time.YearMonth.parse(to)); m = m.plusMonths(1)) {
            add(m.atDay(Math.min(day, m.lengthOfMonth())).toString(), description, amount, category);
        }
    }

    private List<RecurringService.Item> detect() {
        return RecurringService.detect(movements, TODAY);
    }

    private RecurringService.Item only() {
        assertThat(detect()).hasSize(1);
        return detect().getFirst();
    }

    @Test
    void aSubscriptionEveryMonthIsFixedAndActiveWithItsNextDate() {
        monthly("2026-03", "2026-09", 12, "Netflix.com — Pago con tarjeta", -13.99, "Suscripciones");

        RecurringService.Item item = only();

        assertThat(item.name()).isEqualTo("Netflix.com");
        assertThat(item.key()).isEqualTo("comercio:netflix.com");
        assertThat(item.cadence()).isEqualTo("monthly");
        assertThat(item.fixed()).isTrue();
        assertThat(item.active()).isTrue();
        assertThat(item.amount()).isEqualByComparingTo("13.99");
        assertThat(item.monthly()).isEqualByComparingTo("13.99");
        assertThat(item.last_date()).isEqualTo("2026-09-12");
        assertThat(item.next_date()).isEqualTo("2026-10-12");
        assertThat(item.charges()).first().extracting(RecurringService.Charge::date).isEqualTo("2026-09-12"); // newest first
    }

    @Test
    void aPriceThatWentUpAndStayedIsReported() {
        monthly("2025-10", "2026-03", 12, "Netflix.com — Pago con tarjeta", -12.99, "Suscripciones");
        monthly("2026-04", "2026-09", 12, "Netflix.com — Pago con tarjeta", -13.99, "Suscripciones");

        RecurringService.Item item = only();

        assertThat(item.fixed()).isTrue();
        assertThat(item.price_change()).isEqualTo(new RecurringService.PriceChange(eur(12.99), eur(13.99), "2026-04-12"));
    }

    @Test
    void oneOddAmountIsNotAPriceChange() {
        // the rent paid via Bizum and cash, once 680 instead of 650
        add("2026-03-04", "Bizum — ENVIADO: Sin concepto", -650, "Vivienda");
        add("2026-03-27", "Bizum — ENVIADO: Sin concepto", -680, "Vivienda");
        add("2026-05-04", "Bizum — ENVIADO: Sin concepto", -650, "Vivienda");
        add("2026-06-01", "Bizum — ENVIADO: Sin concepto", -650, "Vivienda");
        add("2026-07-13", "Ret. efectivo a debito con tarj. en cajero. aut. — 01820001 999", -650, "Vivienda");
        add("2026-08-06", "Ret. efectivo a debito con tarj. en cajero. aut. — 01820002 999", -650, "Vivienda");
        add("2026-09-11", "Ret. efectivo a debito con tarj. en cajero. aut. — 01820002 999", -650, "Vivienda");

        RecurringService.Item item = only();

        // Paid in ways that name nobody: grouped by category and amount instead
        assertThat(item.key()).isEqualTo("importe:Vivienda:650");
        assertThat(item.grouped_by()).isEqualTo("amount");
        assertThat(item.name()).isEqualTo("Vivienda");
        assertThat(item.charges()).hasSize(7);
        assertThat(item.amount()).isEqualByComparingTo("650");
        assertThat(item.price_change()).isNull();
    }

    @Test
    void differentAmountsWithoutAPayeeAreDifferentGroups() {
        monthly("2026-04", "2026-09", 3, "Transferencia realizada — OBA3KQ9ZT2MXW8RLD", -200, "Ahorro");
        add("2026-04-09", "Transferencia realizada — OBAX7PLMQ2RTV5N8K 3d91c0aa5e7b2f64", -50, "Ahorro");
        add("2026-04-14", "Transferencia realizada — OBAH4WZE9JQ6UYB3C 7a02e5c8d19f4b30", -50, "Ahorro");

        assertThat(detect()).extracting(RecurringService.Item::key).containsExactly("importe:Ahorro:200");
    }

    @Test
    void aPlanThatStoppedIsNoLongerActive() {
        monthly("2026-03", "2026-06", 24, "EduPlan — Pago con tarjeta", -89.90, "Estudios");

        RecurringService.Item item = only();

        assertThat(item.active()).isFalse();
        assertThat(item.name()).isEqualTo("EduPlan");
    }

    @Test
    void aBillThatChangesEachMonthIsVariableWithItsUsualAmount() {
        double[] bills = {72.10, 81.40, 64.02, 45.30, 41.75, 47.90, 52.60, 66.80, 71.25};
        for (int i = 0; i < bills.length; i++) {
            add(LocalDate.of(2026, 1, 7).plusMonths(i).toString(), "Adeudo de recibo — Endesa energia sau", -bills[i], "Vivienda");
        }

        RecurringService.Item item = only();

        assertThat(item.fixed()).isFalse();
        assertThat(item.amount()).isEqualByComparingTo("64.02"); // the median
        assertThat(item.min()).isEqualByComparingTo("41.75");
        assertThat(item.max()).isEqualByComparingTo("81.40");
        assertThat(item.price_change()).isNull();
    }

    @Test
    void aYearlyPaymentCountsATwelfthPerMonth() {
        add("2025-03-05", "Adeudo de recibo — Mapfre seguros hogar", -186.40, "Vivienda");
        add("2026-03-05", "Adeudo de recibo — Mapfre seguros hogar", -186.40, "Vivienda");

        RecurringService.Item item = only();

        assertThat(item.cadence()).isEqualTo("yearly");
        assertThat(item.monthly()).isEqualByComparingTo("15.53");
        assertThat(item.next_date()).isEqualTo("2027-03-05");
        assertThat(item.active()).isTrue();
    }

    @Test
    void aTrainPassKeepsItsPriceDespiteOneTripAYear() {
        monthly("2025-10", "2026-09", 2, "Renfe viajeros — Pago con tarjeta", -21.35, "Transporte");
        add("2026-08-03", "Renfe viajeros — Pago con tarjeta", -95.60, "Transporte");

        RecurringService.Item item = only();

        assertThat(item.fixed()).isTrue();
        assertThat(item.amount()).isEqualByComparingTo("21.35");
    }

    @Test
    void shoppingSeveralTimesAMonthIsNotAFixedExpense() {
        for (String date : new String[]{"2026-03-06", "2026-03-16", "2026-03-18", "2026-03-30", "2026-03-31",
                "2026-08-17", "2026-09-22"}) {
            add(date, "Lidl centro — Pago con tarjeta", -30, "Supermercado");
        }
        for (String date : new String[]{"2026-03-09", "2026-03-25", "2026-04-07", "2026-05-04", "2026-05-26", "2026-09-22"}) {
            add(date, "Farmacia del pilar — Pago con tarjeta", -2.11, "Salud");
        }

        assertThat(detect()).isEmpty();
    }

    @Test
    void aYearlyPaymentOfAnyAmountIsNotEnough() {
        // a holiday booked every July is not a fixed expense
        add("2025-07-04", "Booking.com — Pago con tarjeta", -452.10, "Ocio");
        add("2026-07-04", "Booking.com — Pago con tarjeta", -588.30, "Ocio");

        assertThat(detect()).isEmpty();
    }

    @Test
    void incomeIsNeverAFixedExpense() {
        monthly("2026-01", "2026-08", 28, "Ingreso por nómina o pensión — Soluciones norte sl", 2150, "Nómina / Ingresos");

        assertThat(detect()).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Netflix.com — Pago con tarjeta                        | Netflix.com",
            "Www.amazon*q7x2k5m1z — Pago con tarjeta               | Www.amazon",
            "Dropbox* plus sub — Pago con tarjeta              | Dropbox",
            "Bizum — COMPRA: T MOBILITAT. APP                      | T MOBILITAT. APP",
            "Bizum — ENVIADO: Sin concepto                         | ''",
            "Transferencia realizada — Alquiler piso               | Alquiler piso",
            "Transferencia realizada — OBA8DNFT3VKLC2SXA 5c6e81b7f40d9a2e | ''",
            "Ret. efectivo a debito con tarj. en cajero. aut. — 01820002 999 | ''",
            "Adeudo de recibo — Endesa energia sau                 | Endesa energia sau",
            "Ingreso en efectivo                                   | Ingreso en efectivo",
    })
    void thePayeeIsReadLikeTheRuleSuggestionReadsIt(String description, String payee) {
        assertThat(RecurringService.Payee.nameOf(description)).isEqualTo(payee);
    }
}
