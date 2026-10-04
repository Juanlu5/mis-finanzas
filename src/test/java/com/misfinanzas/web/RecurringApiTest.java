package com.misfinanzas.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecurringApiTest extends ApiTestBase {

    private static final String SAVINGS_KEY = "importe:Ahorro / Inversión:200";

    @BeforeEach
    void movements() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Madrid"));
        for (int i = 0; i < 6; i++) {
            saveTx(today.minusDays(3).minusMonths(i).toString(), "Netflix.com — Pago con tarjeta", -13.99, "Suscripciones");
            // names nobody, in a category whose name has a "/": grouped by amount, hidden through a form parameter
            saveTx(today.minusDays(5).minusMonths(i).toString(), "Transferencia realizada — OBA3KQ9ZT2MXW8RLD", -200, "Ahorro / Inversión");
            saveTx(today.minusMonths(i + 1).withDayOfMonth(28).toString(), "Ingreso por nómina o pensión — Empresa sl", 1500, "Nómina / Ingresos");
        }
        for (int i = 9; i <= 12; i++) {
            saveTx(today.minusMonths(i).withDayOfMonth(24).toString(), "EduPlan — Pago con tarjeta", -89.90, "Estudios");
        }
    }

    @Test
    void listsTheRegularPaymentsWithTheActiveOnesTotal() throws Exception {
        mockMvc.perform(get("/api/recurring").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].key").value(SAVINGS_KEY)) // active ones first, dearest first
                .andExpect(jsonPath("$.items[1].name").value("Netflix.com"))
                .andExpect(jsonPath("$.items[2].name").value("EduPlan"))
                .andExpect(jsonPath("$.items[2].active").value(false))
                .andExpect(jsonPath("$.monthly_total").value(213.99))
                .andExpect(jsonPath("$.yearly_total").value(2567.88))
                .andExpect(jsonPath("$.income_monthly").value(1500.0));
    }

    @Test
    void aHiddenOneStaysListedButOutOfTheTotalsUntilShownAgain() throws Exception {
        mockMvc.perform(post("/api/recurring/hidden").with(auth()).param("key", SAVINGS_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/recurring").with(auth()))
                .andExpect(jsonPath("$.items[?(@.key == '" + SAVINGS_KEY + "')].hidden").value(true))
                .andExpect(jsonPath("$.monthly_total").value(13.99));

        mockMvc.perform(delete("/api/recurring/hidden").with(auth()).param("key", SAVINGS_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/recurring").with(auth()))
                .andExpect(jsonPath("$.monthly_total").value(213.99));
    }

    @Test
    void needsLogin() throws Exception {
        mockMvc.perform(get("/api/recurring")).andExpect(status().isUnauthorized());
    }
}
