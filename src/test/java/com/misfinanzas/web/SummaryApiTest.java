package com.misfinanzas.web;

import com.misfinanzas.entity.Transaction;
import org.junit.jupiter.api.Test;

import static com.misfinanzas.TestMoney.eur;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP layer of the dashboard: routes, parameters, defaults and JSON shape (the maths is in SummaryServiceTest). */
class SummaryApiTest extends ApiTestBase {

    private Transaction saveTx(String date, String category, String account, double amount, Double balance) {
        Transaction t = saveTx(date, "tx", amount, category);
        t.setAccount(account);
        t.setBalance(eur(balance));
        return transactionRepository.saveAndFlush(t);
    }

    @Test
    void summaryForAMonth() throws Exception {
        saveTx("2026-03-01", "Nómina / Ingresos", "Principal", 1500, null);
        saveTx("2026-03-05", "Supermercado", "Principal", -45.30, null);
        saveTx("2026-04-01", "Ocio", "Principal", -999, null);

        mockMvc.perform(get("/api/summary").param("month", "2026-03").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(1500.0))
                .andExpect(jsonPath("$.expense").value(45.3))
                .andExpect(jsonPath("$.balance").value(1454.7))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void summaryWithoutMonthCoversEverythingAndAnEmptyDatabaseIsAllZeros() throws Exception {
        mockMvc.perform(get("/api/summary").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.income").value(0.0));

        saveTx("2026-03-01", "Nómina / Ingresos", "Principal", 100, null);
        saveTx("2026-04-01", "Ocio", "Principal", -30, null);

        mockMvc.perform(get("/api/summary").with(auth()))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.balance").value(70.0));
    }

    @Test
    void monthlyReturnsTheLastTwelveMonthsByDefaultOldestFirst() throws Exception {
        for (int i = 0; i < 14; i++) {   // 2025-01 .. 2026-02
            String month = "%d-%02d".formatted(2025 + i / 12, i % 12 + 1);
            saveTx(month + "-10", "Ocio", "Principal", -(i + 1), null);
        }

        mockMvc.perform(get("/api/summary/monthly").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].month").value("2025-03"))     // 14 months, default keeps the newest 12
                .andExpect(jsonPath("$[11].month").value("2026-02"))
                .andExpect(jsonPath("$[11].expense").value(14.0))
                .andExpect(jsonPath("$[11].income").value(0.0));
    }

    @Test
    void monthlyHonoursTheMonthsParameter() throws Exception {
        saveTx("2026-01-10", "Ocio", "Principal", -1, null);
        saveTx("2026-02-10", "Ocio", "Principal", -2, null);
        saveTx("2026-03-10", "Nómina / Ingresos", "Principal", 500, null);

        mockMvc.perform(get("/api/summary/monthly").param("months", "2").with(auth()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].month").value("2026-02"))
                .andExpect(jsonPath("$[1].month").value("2026-03"))
                .andExpect(jsonPath("$[1].income").value(500.0));
    }

    @Test
    void monthlyOfAnEmptyDatabaseIsAnEmptyList() throws Exception {
        mockMvc.perform(get("/api/summary/monthly").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void balanceHistoryIsChronologicalAndCanBeFilteredByAccount() throws Exception {
        saveTx("2026-03-02", "Ocio", "Principal", -1, 90.0);
        saveTx("2026-03-01", "Ocio", "Principal", -1, 100.0);
        saveTx("2026-03-01", "Ocio", "Ahorro", -1, 5000.0);
        saveTx("2026-03-03", "Ocio", "Principal", -1, null);   // no balance reported: skipped

        mockMvc.perform(get("/api/summary/balance-history").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].date").value("2026-03-01"))
                .andExpect(jsonPath("$[2].date").value("2026-03-02"))
                .andExpect(jsonPath("$[2].balance").value(90.0));

        mockMvc.perform(get("/api/summary/balance-history").param("account", "Ahorro").with(auth()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].balance").value(5000.0));
    }

    @Test
    void balanceHistoryCanBeLimitedToADateRange() throws Exception {
        saveTx("2026-02-28", "Ocio", "Principal", -1, 100.0);
        saveTx("2026-03-15", "Ocio", "Principal", -1, 99.0);
        saveTx("2026-04-01", "Ocio", "Principal", -1, 98.0);

        mockMvc.perform(get("/api/summary/balance-history").param("account", "Principal")
                        .param("from", "2026-03-01").param("to", "2026-03-31").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].date").value("2026-03-15"));
    }

    @Test
    void monthlyOverARangeReturnsEveryMonthInIt() throws Exception {
        saveTx("2026-01-15", "tx", -10, "Ocio");
        saveTx("2026-03-15", "tx", -30, "Ocio");

        mockMvc.perform(get("/api/summary/monthly").param("from", "2025-12").param("to", "2026-03").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].month").value("2025-12"))
                .andExpect(jsonPath("$[1].expense").value(10.0))
                .andExpect(jsonPath("$[2].expense").value(0.0))
                .andExpect(jsonPath("$[3].month").value("2026-03"));
    }

    @Test
    void summaryAndCategoriesTakeARangeOfMonths() throws Exception {
        saveTx("2026-01-15", "tx", -10, "Ocio");
        saveTx("2026-02-15", "tx", -30, "Supermercado");
        saveTx("2026-03-15", "tx", -500, "Ocio");

        mockMvc.perform(get("/api/summary").param("from", "2026-01").param("to", "2026-02").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expense").value(40.0))
                .andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(get("/api/summary/by-category").param("from", "2026-01").param("to", "2026-02").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].category").value("Supermercado"));
        mockMvc.perform(get("/api/summary").param("from", "2026-02").with(auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void categoriesOverARangeTakeAnUntilDate() throws Exception {
        saveTx("2026-01-10", "tx", -10, "Ocio");
        saveTx("2026-02-20", "tx", -30, "Ocio");
        saveTx("2026-02-25", "tx", -500, "Supermercado");

        mockMvc.perform(get("/api/summary/by-category").param("from", "2026-01").param("to", "2026-02")
                        .param("until", "2026-02-21").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("Ocio"))
                .andExpect(jsonPath("$[0].amount").value(40.0));
        mockMvc.perform(get("/api/summary/by-category").param("until", "2026-02-21").with(auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summaryTakesAnUntilDateAndCurrentBalanceIsServed() throws Exception {
        saveTx("2026-08-10", "Ocio", "Principal", -20, 1000.0);
        saveTx("2026-08-31", "Nómina / Ingresos", "Principal", 1714.16, 2714.16);

        mockMvc.perform(get("/api/summary").param("month", "2026-08").param("until", "2026-08-24").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(0.0))
                .andExpect(jsonPath("$.expense").value(20.0));
        mockMvc.perform(get("/api/summary/current-balance").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2714.16))
                .andExpect(jsonPath("$.date").value("2026-08-31"))
                .andExpect(jsonPath("$.accounts[0].account").value("Principal"));
        mockMvc.perform(get("/api/summary").param("until", "ayer").with(auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMalformedOrInvertedPeriodIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/summary/monthly").param("from", "2026-13").param("to", "2026-12").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Formato de mes inválido, usa AAAA-MM"));
        mockMvc.perform(get("/api/summary/monthly").param("from", "2026-05").param("to", "2026-04").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/summary/monthly").param("from", "2026-05").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/summary/monthly").param("from", "0001-01").param("to", "9999-12").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/summary/balance-history").param("from", "no-es-fecha").with(auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void byCategoryListsExpensesBiggestFirstForAMonth() throws Exception {
        saveTx("2026-03-01", "Ocio", "Principal", -10, null);
        saveTx("2026-03-02", "Supermercado", "Principal", -30, null);
        saveTx("2026-03-03", "Ocio", "Principal", -25, null);
        saveTx("2026-03-04", "Nómina / Ingresos", "Principal", 1500, null);   // income never listed
        saveTx("2026-04-01", "Salud", "Principal", -99, null);

        mockMvc.perform(get("/api/summary/by-category").param("month", "2026-03").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].category").value("Ocio"))
                .andExpect(jsonPath("$[0].amount").value(35.0))
                .andExpect(jsonPath("$[1].category").value("Supermercado"));

        mockMvc.perform(get("/api/summary/by-category").with(auth()))
                .andExpect(jsonPath("$.length()").value(3));   // no month: everything, incl. April's Salud
    }

    @Test
    void monthsAvailableAreDistinctAndNewestFirst() throws Exception {
        saveTx("2026-03-01", "Ocio", "Principal", -1, null);
        saveTx("2026-03-02", "Ocio", "Principal", -1, null);
        saveTx("2026-05-01", "Ocio", "Principal", -1, null);

        mockMvc.perform(get("/api/summary/months-available").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("2026-05"))
                .andExpect(jsonPath("$[1]").value("2026-03"));
    }
}
