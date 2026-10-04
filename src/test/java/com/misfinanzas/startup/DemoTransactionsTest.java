package com.misfinanzas.startup;

import com.misfinanzas.dto.ImportRow;
import com.misfinanzas.entity.Rule;
import com.misfinanzas.service.CategorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DemoTransactionsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);
    private final CategorizationService categorization = new CategorizationService();

    private static List<Rule> demoRules() {
        List<Rule> rules = new ArrayList<>();
        for (String[] entry : Stream.concat(DataSeedRunner.DEFAULT_RULES.stream(), DemoDataRunner.DEMO_RULES.stream()).toList()) {
            Rule rule = new Rule();
            rule.setKeyword(entry[0]);
            rule.setCategory(entry[1]);
            rules.add(rule);
        }
        return rules;
    }

    private static List<String> inMonth(List<ImportRow> rows, YearMonth month) {
        return rows.stream().filter(r -> YearMonth.from(r.date()).equals(month))
                .map(r -> r.date() + " " + r.description() + " " + r.amount()).toList();
    }

    @Test
    void coversTwoYearsUpToTodayAndNothingAfter() {
        List<ImportRow> rows = DemoTransactions.build(TODAY);

        assertThat(rows).isNotEmpty();
        assertThat(YearMonth.from(rows.getFirst().date())).isEqualTo(YearMonth.of(2024, 10));
        assertThat(rows).allSatisfy(r -> assertThat(r.date()).isBeforeOrEqualTo(TODAY));
        assertThat(rows).extracting(ImportRow::date).isSorted();
        assertThat(rows).allSatisfy(r -> assertThat(r.account()).isEqualTo(DemoTransactions.ACCOUNT));
    }

    @Test
    void aMonthComesOutTheSameWhateverDayTheDemoStarts() {
        List<ImportRow> now = DemoTransactions.build(TODAY);
        List<ImportRow> later = DemoTransactions.build(LocalDate.of(2027, 2, 3));

        assertThat(inMonth(later, YearMonth.of(2026, 6))).isNotEmpty().isEqualTo(inMonth(now, YearMonth.of(2026, 6)));
    }

    @Test
    void eachBalanceIsThePreviousOnePlusTheAmountAndTheAccountGrows() {
        List<ImportRow> rows = DemoTransactions.build(TODAY);

        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).balance()).isEqualByComparingTo(rows.get(i - 1).balance().add(rows.get(i).amount()));
        }
        assertThat(rows).allSatisfy(r -> assertThat(r.balance()).isPositive());
        assertThat(rows.getLast().balance()).isGreaterThan(rows.getFirst().balance());
        assertThat(rows).allSatisfy(r -> assertThat(r.amount().scale()).isEqualTo(2));
    }

    @Test
    void onlyTheMovementsLeftForTryingRulesStayUncategorized() {
        List<Rule> rules = demoRules();

        List<String> uncategorized = DemoTransactions.build(TODAY).stream().map(ImportRow::description)
                .filter(d -> categorization.applyRules(d, rules) == null).distinct().toList();

        assertThat(uncategorized).isNotEmpty().allSatisfy(d -> assertThat(d).satisfiesAnyOf(
                s -> assertThat(s).startsWith("Bizum — "),
                s -> assertThat(s).startsWith("Bonpreu"),
                s -> assertThat(s).startsWith("Bar el tomate")));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Ingreso por nómina o pensión — Soluciones norte sl  | Nómina / Ingresos",
            "Transferencia realizada — Alquiler piso             | Vivienda",
            "Transferencia realizada — Aportacion fondo indexado | Ahorro / Inversión",
            "Adeudo de recibo — Endesa energia sau               | Vivienda",
            "Adeudo de recibo — Digi spain telecom               | Suscripciones",
            "Adeudo de recibo — Gimnasio fitpark                 | Ocio",
            "Adeudo de recibo — Mapfre seguros hogar             | Vivienda",
            "Transferencia recibida — Devolucion aeat irpf       | Otros ingresos",
            "Dia sants — Pago con tarjeta                        | Supermercado",
            "Www.amazon*k2j4x9ab — Pago con tarjeta              | Compras",
            "Mediamarkt — Pago con tarjeta                       | Compras",
            "Cines verdi — Pago con tarjeta                      | Ocio",
            "Hbo max — Pago con tarjeta                          | Suscripciones",
            "Renfe viajeros — Pago con tarjeta                   | Transporte",
            "Cafeteria el mirador — Pago con tarjeta             | Restaurantes",
            "Farmacia sant antoni — Pago con tarjeta             | Salud",
            "Ret. efectivo a debito con tarj. en cajero. aut. — 01820002 999 | Otros gastos",
    })
    void theRegularPayeesLandInTheirCategory(String description, String expected) {
        assertThat(categorization.applyRules(description, demoRules())).isEqualTo(expected);
    }

    @Test
    void thereAreExtraPaysInJuneAndDecember() {
        List<ImportRow> rows = DemoTransactions.build(TODAY);

        long payrollsInJune = rows.stream().filter(r -> YearMonth.from(r.date()).equals(YearMonth.of(2026, 6)))
                .filter(r -> r.description().startsWith("Ingreso por nómina")).count();
        assertThat(payrollsInJune).isEqualTo(2);
        assertThat(rows.stream().filter(r -> r.amount().compareTo(BigDecimal.ZERO) > 0)
                .filter(r -> r.description().startsWith("Ingreso por nómina")).map(ImportRow::amount).distinct())
                .as("a pay rise each year").hasSizeGreaterThan(1);
    }
}
