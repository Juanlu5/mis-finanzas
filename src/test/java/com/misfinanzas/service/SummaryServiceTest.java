package com.misfinanzas.service;

import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.YearMonth;

import static com.misfinanzas.TestMoney.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SummaryService.class)
class SummaryServiceTest {

    @Autowired
    SummaryService service;
    @Autowired
    TransactionRepository repository;

    private int counter;

    private Transaction tx(String date, String month, String category, String account, double amount, Double balance) {
        Transaction t = new Transaction();
        t.setDate(LocalDate.parse(date));
        t.setAccountingMonth(month);
        t.setDescription("tx " + counter);
        t.setCategory(category);
        t.setAccount(account);
        t.setAmount(eur(amount));
        t.setBalance(eur(balance));
        t.setDedupeKey("k" + counter++);
        return repository.saveAndFlush(t);
    }

    private Transaction tx(String date, String category, double amount) {
        return tx(date, date.substring(0, 7), category, "Principal", amount, null);
    }

    // ---------- summary ----------

    @Test
    void summaryTotalsIncomeExpenseAndBalanceForAMonth() {
        tx("2026-03-01", "Nómina / Ingresos", 1500);
        tx("2026-03-05", "Supermercado", -45.30);
        tx("2026-03-06", "Ocio", -20);
        tx("2026-04-01", "Supermercado", -999);   // other month

        SummaryService.Summary s = service.summary("2026-03");

        assertThat(s.income()).isEqualTo(eur(1500.0));
        assertThat(s.expense()).isEqualTo(eur(65.3));
        assertThat(s.balance()).isEqualTo(eur(1434.7));
        assertThat(s.count()).isEqualTo(3);
    }

    @Test
    void summaryWithoutMonthAggregatesEverything() {
        tx("2026-03-01", "Nómina / Ingresos", 100);
        tx("2026-04-01", "Ocio", -30);

        SummaryService.Summary s = service.summary(null);

        assertThat(s).isEqualTo(new SummaryService.Summary(eur(100.0), eur(30.0), eur(70.0), 2));
    }

    @Test
    void summaryOfAnEmptyMonthIsAllZeros() {
        assertThat(service.summary("2030-01")).isEqualTo(new SummaryService.Summary(eur(0.0), eur(0.0), eur(0.0), 0));
    }

    @Test
    void summaryRoundsFloatingPointNoiseToCents() {
        tx("2026-03-01", "Ocio", -0.1);
        tx("2026-03-02", "Ocio", -0.2);

        assertThat(service.summary("2026-03").expense()).isEqualTo(eur(0.3)); // exact: in doubles, 0.1 + 0.2 = 0.30000000000000004
    }

    @Test
    void zeroAmountCountsAsATransactionButNeitherIncomeNorExpense() {
        tx("2026-03-01", "Ocio", 0);

        assertThat(service.summary("2026-03")).isEqualTo(new SummaryService.Summary(eur(0.0), eur(0.0), eur(0.0), 1));
    }

    @Test
    void summaryGroupsByAccountingMonthNotByDate() {
        // a salary that lands on 31 March but is reported under April
        tx("2026-03-31", "2026-04", "Nómina / Ingresos", "Principal", 1500, null);

        assertThat(service.summary("2026-03").count()).isZero();
        assertThat(service.summary("2026-04").income()).isEqualTo(eur(1500.0));
    }

    // ---------- monthly ----------

    @Test
    void monthlyTotalsAreSortedAscendingPerAccountingMonth() {
        tx("2026-04-10", "Ocio", -10);
        tx("2026-03-01", "Nómina / Ingresos", 1000);
        tx("2026-03-05", "Ocio", -40);
        tx("2026-03-06", "Ocio", -10);

        assertThat(service.summaryMonthly(12))
                .extracting(SummaryService.MonthlyTotal::month, SummaryService.MonthlyTotal::income, SummaryService.MonthlyTotal::expense)
                .containsExactly(tuple("2026-03", eur(1000.0), eur(50.0)), tuple("2026-04", eur(0.0), eur(10.0)));
    }

    @Test
    void monthlyKeepsOnlyTheMostRecentMonthsRequested() {
        tx("2026-01-10", "Ocio", -1);
        tx("2026-02-10", "Ocio", -2);
        tx("2026-03-10", "Ocio", -3);
        tx("2026-04-10", "Ocio", -4);

        assertThat(service.summaryMonthly(2)).extracting(SummaryService.MonthlyTotal::month)
                .containsExactly("2026-03", "2026-04");
        assertThat(service.summaryMonthly(100)).hasSize(4);
    }

    @Test
    void monthlyIgnoresTransactionsWithoutAccountingMonth() {
        tx("2026-03-10", null, "Ocio", "Principal", -5, null);
        tx("2026-03-11", "Ocio", -7);

        assertThat(service.summaryMonthly(12)).extracting(SummaryService.MonthlyTotal::expense).containsExactly(eur(7.0));
    }

    // ---------- balance history ----------

    @Test
    void summaryOverARangeAddsUpItsAccountingMonths() {
        tx("2026-01-31", "2026-02", "Nómina / Ingresos", "Principal", 1500, null); // dated January, counted in February
        tx("2026-03-10", "Ocio", -40);
        tx("2026-04-01", "Ocio", -999);                                             // after the range

        SummaryService.Summary s = service.summary(YearMonth.parse("2026-02"), YearMonth.parse("2026-03"));

        assertThat(s.income()).isEqualTo(eur(1500.0));
        assertThat(s.expense()).isEqualTo(eur(40.0));
        assertThat(s.balance()).isEqualTo(eur(1460.0));
        assertThat(s.count()).isEqualTo(2);
    }

    @Test
    void byCategoryOverARangeOnlyCountsItsMonths() {
        tx("2026-02-10", "Ocio", -40);
        tx("2026-03-10", "Ocio", -10);
        tx("2026-03-11", "Supermercado", -60);
        tx("2026-04-01", "Ocio", -999);

        assertThat(service.summaryByCategory(YearMonth.parse("2026-02"), YearMonth.parse("2026-03")))
                .extracting(SummaryService.CategoryTotal::category, SummaryService.CategoryTotal::amount)
                .containsExactly(tuple("Supermercado", eur(60.0)), tuple("Ocio", eur(50.0)));
    }

    @Test
    void summaryUntilADateLeavesOutWhatCameAfterIt() {
        tx("2026-08-10", "Ocio", -20);
        tx("2026-08-24", "Ocio", -30);
        tx("2026-08-31", "Nómina / Ingresos", 1714.16);   // payroll on the last day: not there yet on the 24th

        SummaryService.Summary s = service.summary("2026-08", LocalDate.parse("2026-08-24"));

        assertThat(s.income()).isEqualTo(eur(0.0));
        assertThat(s.expense()).isEqualTo(eur(50.0));
        assertThat(service.summary(YearMonth.parse("2026-07"), YearMonth.parse("2026-08"), LocalDate.parse("2026-08-24")).expense())
                .isEqualTo(eur(50.0));
    }

    @Test
    void currentBalanceIsTheNewestKnownBalanceOfEachAccountAddedUp() {
        tx("2026-09-20", "2026-09", "Ocio", "Principal", -10, 900.0);
        // two on the newest day, stored newest first as a bank download does: 800 is the day's last
        tx("2026-09-23", "2026-09", "Ocio", "Principal", -50, 800.0);
        tx("2026-09-23", "2026-09", "Ocio", "Principal", -50, 850.0);
        tx("2026-09-24", "2026-09", "Ocio", "Principal", -5, null);   // no balance: doesn't count
        tx("2026-09-10", "2026-09", "Ocio", "Ahorro", 100, 5000.0);

        SummaryService.CurrentBalance now = service.currentBalance();

        assertThat(now.total()).isEqualTo(eur(5800.0));
        assertThat(now.date()).isEqualTo("2026-09-10");   // the stalest account's date: never claims to be fresher
        assertThat(now.accounts()).extracting(SummaryService.AccountBalance::account, SummaryService.AccountBalance::balance)
                .containsExactly(tuple("Ahorro", eur(5000.0)), tuple("Principal", eur(800.0)));
    }

    @Test
    void noBalancesAtAllMeansNoCurrentBalance() {
        tx("2026-09-20", "Ocio", -10);

        SummaryService.CurrentBalance now = service.currentBalance();

        assertThat(now.total()).isNull();
        assertThat(now.accounts()).isEmpty();
    }

    @Test
    void balanceHistoryHasOnePointPerDayInDateOrderAndSkipsRowsWithoutBalance() {
        tx("2026-03-02", "2026-03", "Ocio", "Principal", -10, 90.0);
        tx("2026-03-01", "2026-03", "Ocio", "Principal", -1, 100.0);
        tx("2026-03-02", "2026-03", "Ocio", "Principal", -10, 80.0);     // same day, after the 90 (starts from it)
        tx("2026-03-03", "2026-03", "Ocio", "Principal", -1, null);       // no balance reported

        assertThat(service.balanceHistory(null))
                .extracting(SummaryService.BalancePoint::date, SummaryService.BalancePoint::balance)
                .containsExactly(tuple("2026-03-01", eur(100.0)), tuple("2026-03-02", eur(80.0)));
    }

    @Test
    void theDaysLastMovementIsFoundFromTheBalancesNotTheStorageOrder() {
        // A bank download stores a day's movements newest first: here the 70 is the day's last balance
        // (100 - 10 = 90, 90 - 20 = 70) but it was stored first.
        tx("2026-03-02", "2026-03", "Ocio", "Principal", -20, 70.0);
        tx("2026-03-02", "2026-03", "Ocio", "Principal", -10, 90.0);

        assertThat(service.balanceHistory(null)).extracting(SummaryService.BalancePoint::balance).containsExactly(eur(70.0));
    }

    @Test
    void balancesThatDontChainFallBackToTheMostRecentlyStored() {
        tx("2026-03-02", "2026-03", "Ocio", "Principal", -1, 500.0);
        tx("2026-03-02", "2026-03", "Ocio", "Principal", -1, 40.0);

        assertThat(service.balanceHistory(null)).extracting(SummaryService.BalancePoint::balance).containsExactly(eur(40.0));
    }

    @Test
    void balanceHistoryCanBeLimitedToADateRange() {
        tx("2026-02-28", "2026-02", "Ocio", "Principal", -1, 100.0);
        tx("2026-03-01", "2026-03", "Ocio", "Principal", -1, 99.0);
        tx("2026-03-31", "2026-03", "Ocio", "Principal", -1, 98.0);
        tx("2026-04-01", "2026-04", "Ocio", "Principal", -1, 97.0);

        assertThat(service.balanceHistory(null, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31")))
                .extracting(SummaryService.BalancePoint::date)
                .containsExactly("2026-03-01", "2026-03-31");
        assertThat(service.balanceHistory(null, LocalDate.parse("2026-03-15"), null))
                .extracting(SummaryService.BalancePoint::date)
                .containsExactly("2026-03-31", "2026-04-01");
    }

    @Test
    void monthlyOverARangeListsEveryMonthIncludingEmptyOnes() {
        tx("2026-01-10", "Ocio", -5);                  // before the range
        tx("2026-02-01", "Nómina / Ingresos", 1500);
        tx("2026-02-03", "Ocio", -20.5);
        tx("2026-04-09", "Ocio", -30);                 // March has nothing
        tx("2026-05-01", "Ocio", -999);                // after the range

        assertThat(service.summaryMonthly(YearMonth.parse("2026-02"), YearMonth.parse("2026-04")))
                .extracting(SummaryService.MonthlyTotal::month, SummaryService.MonthlyTotal::income,
                        SummaryService.MonthlyTotal::expense)
                .containsExactly(tuple("2026-02", eur(1500.0), eur(20.5)), tuple("2026-03", eur(0.0), eur(0.0)), tuple("2026-04", eur(0.0), eur(30.0)));
    }

    @Test
    void monthlyOverARangeUsesTheAccountingMonthNotTheDate() {
        tx("2026-01-30", "2026-02", "Nómina / Ingresos", "Principal", 1500, null); // paid in January, counted in February

        assertThat(service.summaryMonthly(YearMonth.parse("2026-01"), YearMonth.parse("2026-02")))
                .extracting(SummaryService.MonthlyTotal::income)
                .containsExactly(eur(0.0), eur(1500.0));
    }

    @Test
    void balanceHistoryCanBeFilteredByAccount() {
        tx("2026-03-01", "2026-03", "Ocio", "Principal", -1, 100.0);
        tx("2026-03-01", "2026-03", "Ocio", "Ahorro", -1, 5000.0);

        assertThat(service.balanceHistory("Ahorro")).extracting(SummaryService.BalancePoint::balance)
                .containsExactly(eur(5000.0));
    }

    // ---------- by category ----------

    @Test
    void byCategoryAddsUpExpensesAndSortsBiggestFirst() {
        tx("2026-03-01", "Ocio", -10);
        tx("2026-03-02", "Supermercado", -30);
        tx("2026-03-03", "Ocio", -25);
        tx("2026-03-04", "Nómina / Ingresos", 1500);   // income never appears
        tx("2026-04-01", "Salud", -99);                 // other month

        assertThat(service.summaryByCategory("2026-03"))
                .extracting(SummaryService.CategoryTotal::category, SummaryService.CategoryTotal::amount)
                .containsExactly(tuple("Ocio", eur(35.0)), tuple("Supermercado", eur(30.0)));
    }

    @Test
    void byCategoryWithoutMonthCoversEverything() {
        tx("2026-03-01", "Ocio", -10);
        tx("2026-04-01", "Ocio", -5);

        assertThat(service.summaryByCategory(null)).extracting(SummaryService.CategoryTotal::amount).containsExactly(eur(15.0));
    }

    // A refund is a positive amount, so it is neither counted as spending nor netted against its category.
    @Test
    void refundsAreNotNettedAgainstTheirCategory() {
        tx("2026-03-01", "Compras", -100);
        tx("2026-03-02", "Compras", 40);

        assertThat(service.summaryByCategory("2026-03")).extracting(SummaryService.CategoryTotal::amount)
                .containsExactly(eur(100.0));
    }

    // ---------- months ----------

    @Test
    void monthsAvailableAreDistinctNewestFirst() {
        tx("2026-03-01", "Ocio", -1);
        tx("2026-03-02", "Ocio", -1);
        tx("2026-05-01", "Ocio", -1);
        tx("2026-04-01", null, "Ocio", "Principal", -1, null);

        assertThat(service.monthsAvailable()).containsExactly("2026-05", "2026-03");
    }
}
