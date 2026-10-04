package com.misfinanzas.web;

import com.misfinanzas.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransactionApiTest extends ApiTestBase {

    @Test
    void listIsSortedNewestFirstAndFilterable() throws Exception {
        saveTx("2026-03-10", "Vieja", -1, "Ocio");
        saveTx("2026-04-02", "Nueva", -2, "Ocio");
        saveTx("2026-04-02", "Nueva bis", -3, "Salud");

        mockMvc.perform(get("/api/transactions").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                // same date: higher id first
                .andExpect(jsonPath("$.items[0].description").value("Nueva bis"))
                .andExpect(jsonPath("$.items[1].description").value("Nueva"))
                .andExpect(jsonPath("$.items[2].description").value("Vieja"));

        mockMvc.perform(get("/api/transactions").param("month", "2026-04").param("category", "Ocio").with(auth()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Nueva"))
                .andExpect(jsonPath("$.items[0].accounting_month").value("2026-04"));

        mockMvc.perform(get("/api/transactions").param("search", "BIS").with(auth()))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listCanBeLimitedToARangeOfAccountingMonths() throws Exception {
        saveTx("2026-01-20", "enero", -1, "Ocio");
        saveTx("2026-02-20", "febrero", -1, "Ocio");
        saveTx("2026-03-20", "marzo", -1, "Ocio");
        saveTx("2026-04-20", "abril", -1, "Ocio");
        saveTx("2026-03-21", "marzo, otra categoría", -1, "Salud");

        mockMvc.perform(get("/api/transactions").param("month_from", "2026-02").param("month_to", "2026-03")
                        .param("category", "Ocio").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].description").value("marzo"))
                .andExpect(jsonPath("$.items[1].description").value("febrero"));
    }

    @Test
    void aHalfOpenMalformedOrInvertedMonthRangeIsRejected() throws Exception {
        mockMvc.perform(get("/api/transactions").param("month_from", "2026-02").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/transactions").param("month_from", "2026-2").param("month_to", "2026-03").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Formato de mes inválido, usa AAAA-MM"));
        mockMvc.perform(get("/api/transactions").param("month_from", "2026-04").param("month_to", "2026-03").with(auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listCanBeFilteredByKindAndBySizeOfTheAmount() throws Exception {
        saveTx("2026-03-01", "café", -2.5, "Ocio");
        saveTx("2026-03-02", "súper", -45.3, "Supermercado");
        saveTx("2026-03-03", "alquiler", -700, "Casa");
        saveTx("2026-03-04", "bizum recibido", 45.3, "Nómina / Ingresos");
        saveTx("2026-03-05", "nómina", 1700, "Nómina / Ingresos");

        // "expenses of 100 € or more": the size of the amount, the sign comes from kind
        mockMvc.perform(get("/api/transactions").param("kind", "expense").param("amount_min", "100").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].description").value("alquiler"));

        // an exact amount finds both signs, ends included
        mockMvc.perform(get("/api/transactions").param("amount_min", "45.30").param("amount_max", "45.30").with(auth()))
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get("/api/transactions").param("kind", "income").with(auth()))
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get("/api/transactions").param("amount_max", "50").with(auth()))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void anInvalidKindOrAmountFilterIsRejected() throws Exception {
        mockMvc.perform(get("/api/transactions").param("kind", "gastos").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/transactions").param("amount_min", "-5").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El importe del filtro debe ser un número positivo"));
        mockMvc.perform(get("/api/transactions").param("amount_max", "NaN").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/transactions").param("amount_min", "Infinity").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/transactions").param("amount_min", "abc").with(auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/transactions").param("amount_min", "100").param("amount_max", "10").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El importe mínimo no puede ser mayor que el máximo"));
    }

    @Test
    void listIsPaginatedAndReportsTheTotalOfTheFilteredResult() throws Exception {
        for (int day = 1; day <= 7; day++) saveTx("2026-04-0" + day, "Gasto " + day, -day, "Ocio");
        saveTx("2026-05-01", "Otro mes", -1, "Salud");

        mockMvc.perform(get("/api/transactions").param("month", "2026-04").param("size", "3").with(auth()))
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.pages").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].description").value("Gasto 7"));

        // last page holds the remainder
        mockMvc.perform(get("/api/transactions").param("month", "2026-04").param("size", "3").param("page", "2")
                        .with(auth()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Gasto 1"));

        // past the end: empty page, but the total is still there so the UI can go back
        mockMvc.perform(get("/api/transactions").param("month", "2026-04").param("size", "3").param("page", "9")
                        .with(auth()))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(7));
    }

    @Test
    void emptyResultIsAnEmptyPageNotAnError() throws Exception {
        mockMvc.perform(get("/api/transactions").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.pages").value(0));
    }

    @Test
    void canSortByAmountAndByTextInBothDirections() throws Exception {
        saveTx("2026-04-01", "banco", -50, "Ocio");
        saveTx("2026-04-02", "Zapatos", -10, "Ocio");
        saveTx("2026-04-03", "Ahorro", 200, "Ocio");

        mockMvc.perform(get("/api/transactions").param("sort", "amount").param("dir", "asc").with(auth()))
                .andExpect(jsonPath("$.items[0].amount").value(-50.0))
                .andExpect(jsonPath("$.items[2].amount").value(200.0));
        mockMvc.perform(get("/api/transactions").param("sort", "amount").param("dir", "desc").with(auth()))
                .andExpect(jsonPath("$.items[0].amount").value(200.0));

        // case-insensitive: "banco" sits between "Ahorro" and "Zapatos", not after every capital
        mockMvc.perform(get("/api/transactions").param("sort", "description").param("dir", "asc").with(auth()))
                .andExpect(jsonPath("$.items[0].description").value("Ahorro"))
                .andExpect(jsonPath("$.items[1].description").value("banco"))
                .andExpect(jsonPath("$.items[2].description").value("Zapatos"));
    }

    @Test
    void everySortableColumnIsAccepted() throws Exception {
        saveTx("2026-04-01", "Cena", -5, "Ocio");
        for (String sort : new String[]{"date", "accounting_month", "description", "account", "category", "amount"}) {
            mockMvc.perform(get("/api/transactions").param("sort", sort).with(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }
    }

    // Rows with equal sort values must keep a fixed order, or a row can show up on two pages and
    // another on none while the user pages through.
    @Test
    void equalSortValuesPageWithoutRepeatsOrGaps() throws Exception {
        for (int i = 0; i < 5; i++) saveTx("2026-04-01", "Igual " + i, -5, "Ocio");

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int page = 0; page < 3; page++) {
            String body = mockMvc.perform(get("/api/transactions").param("sort", "amount").param("size", "2")
                            .param("page", String.valueOf(page)).with(auth()))
                    .andReturn().getResponse().getContentAsString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"description\":\"([^\"]+)\"").matcher(body);
            while (m.find()) assertThat(seen.add(m.group(1))).as("repeated row %s", m.group(1)).isTrue();
        }
        assertThat(seen).hasSize(5);
    }

    @Test
    void invalidSortOrPaginationParametersAreRejected() throws Exception {
        for (String[] bad : new String[][]{
                {"sort", "dedupe_key"}, {"sort", "id; drop table"}, {"dir", "up"},
                {"page", "-1"}, {"size", "0"}, {"size", "201"}}) {
            mockMvc.perform(get("/api/transactions").param(bad[0], bad[1]).with(auth()))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/transactions").param("sort", "dedupe_key").with(auth()))
                .andExpect(jsonPath("$.detail").value("Campo de ordenación inválido: dedupe_key"));
    }

    // The frontend sends a form-encoded body on PATCH. Spring's FormContentFilter turns it into request
    // parameters, but only if nothing reads the raw body first. .content(...) (not .param(...)) is what
    // sends a real body through the filter chain, so this is the regression test for that.
    @Test
    void patchAcceptsAFormEncodedBody() throws Exception {
        Transaction t = saveTx("2026-03-10", "Cena", -30, "Sin categorizar");

        mockMvc.perform(patch("/api/transactions/{id}", t.getId()).with(auth())
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .content("category=Restaurantes&description=Cena+con+amigos&accounting_month=2026-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        Transaction saved = transactionRepository.findById(t.getId()).orElseThrow();
        assertThat(saved.getCategory()).isEqualTo("Restaurantes");
        assertThat(saved.getDescription()).isEqualTo("Cena con amigos");
        assertThat(saved.getAccountingMonth()).isEqualTo("2026-02");
        assertThat(saved.getDate().toString()).isEqualTo("2026-03-10"); // date untouched by the accounting month
    }

    @Test
    void patchOnlyChangesTheSentFields() throws Exception {
        Transaction t = saveTx("2026-03-10", "Cena", -30, "Ocio");

        mockMvc.perform(patch("/api/transactions/{id}", t.getId()).with(auth())
                        .contentType(APPLICATION_FORM_URLENCODED).content("category=Salud"))
                .andExpect(status().isOk());

        Transaction saved = transactionRepository.findById(t.getId()).orElseThrow();
        assertThat(saved.getCategory()).isEqualTo("Salud");
        assertThat(saved.getDescription()).isEqualTo("Cena");
    }

    @Test
    void patchRejectsAMalformedAccountingMonth() throws Exception {
        Transaction t = saveTx("2026-03-10", "Cena", -30, "Ocio");

        for (String bad : new String[]{"2026-3", "03-2026", "marzo", "2026-03-10"}) {
            mockMvc.perform(patch("/api/transactions/{id}", t.getId()).with(auth())
                            .contentType(APPLICATION_FORM_URLENCODED).content("accounting_month=" + bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Formato de mes inválido, usa AAAA-MM"));
        }
    }

    @Test
    void unknownIdIs404WithFastApiStyleDetail() throws Exception {
        mockMvc.perform(patch("/api/transactions/{id}", 999999).with(auth())
                        .contentType(APPLICATION_FORM_URLENCODED).content("category=Ocio"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No encontrado"));
        mockMvc.perform(delete("/api/transactions/{id}", 999999).with(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No encontrado"));
    }

    @Test
    void deleteRemovesTheTransaction() throws Exception {
        Transaction t = saveTx("2026-03-10", "Borrame", -1, "Ocio");

        mockMvc.perform(delete("/api/transactions/{id}", t.getId()).with(auth())).andExpect(status().isOk());

        assertThat(transactionRepository.findById(t.getId())).isEmpty();
    }

    @Test
    void bulkCategoryUpdatesEveryListedTransaction() throws Exception {
        Transaction a = saveTx("2026-03-10", "A", -1, "Sin categorizar");
        Transaction b = saveTx("2026-03-11", "B", -2, "Sin categorizar");
        Transaction untouched = saveTx("2026-03-12", "C", -3, "Sin categorizar");

        mockMvc.perform(post("/api/transactions/bulk-category").with(auth()).contentType(APPLICATION_JSON)
                        .content("{\"ids\":[%d,%d],\"category\":\"Ocio\"}".formatted(a.getId(), b.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        assertThat(transactionRepository.findById(a.getId()).orElseThrow().getCategory()).isEqualTo("Ocio");
        assertThat(transactionRepository.findById(b.getId()).orElseThrow().getCategory()).isEqualTo("Ocio");
        assertThat(transactionRepository.findById(untouched.getId()).orElseThrow().getCategory())
                .isEqualTo("Sin categorizar");
    }

    @Test
    void bulkDeleteRemovesTheListedTransactions() throws Exception {
        Transaction a = saveTx("2026-03-10", "A", -1, "Ocio");
        Transaction keep = saveTx("2026-03-11", "B", -2, "Ocio");

        mockMvc.perform(post("/api/transactions/bulk-delete").with(auth()).contentType(APPLICATION_JSON)
                        .content("{\"ids\":[%d]}".formatted(a.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        assertThat(transactionRepository.findById(a.getId())).isEmpty();
        assertThat(transactionRepository.findById(keep.getId())).isPresent();
    }

    @Test
    void bulkEndpointsRejectAnEmptyOrMissingIdList() throws Exception {
        for (String path : new String[]{"/api/transactions/bulk-category", "/api/transactions/bulk-delete"}) {
            for (String body : new String[]{"{\"ids\":[],\"category\":\"Ocio\"}", "{\"category\":\"Ocio\"}"}) {
                mockMvc.perform(post(path).with(auth()).contentType(APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.detail").value("No se han indicado transacciones"));
            }
        }
    }

    @Test
    void accountsListsDistinctAccounts() throws Exception {
        saveTx("2026-03-10", "A", -1, "Ocio");

        mockMvc.perform(get("/api/accounts").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Principal"));
    }
}
