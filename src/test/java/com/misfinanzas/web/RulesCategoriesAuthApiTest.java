package com.misfinanzas.web;

import com.misfinanzas.entity.Rule;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.CategoryRepository;
import com.misfinanzas.repository.RuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RulesCategoriesAuthApiTest extends ApiTestBase {

    @Autowired
    RuleRepository ruleRepository;
    @Autowired
    CategoryRepository categoryRepository;

    private static com.misfinanzas.entity.Category category(String name) {
        com.misfinanzas.entity.Category c = new com.misfinanzas.entity.Category();
        c.setName(name);
        c.setKind("expense");
        return c;
    }

    private void addRule(String keyword, String category) {
        Rule rule = new Rule();
        rule.setKeyword(keyword);
        rule.setCategory(category);
        ruleRepository.saveAndFlush(rule);
    }

    // ---------- rules ----------

    @Test
    void ruleCanBeAddedListedAndDeleted() throws Exception {
        mockMvc.perform(post("/api/rules").param("keyword", "gimnasio").param("category", "Salud").with(auth()))
                .andExpect(status().isOk());

        Rule saved = ruleRepository.findAll().stream().filter(r -> r.getKeyword().equals("gimnasio")).findFirst().orElseThrow();
        mockMvc.perform(get("/api/rules").with(auth()))
                .andExpect(jsonPath("$[?(@.keyword=='gimnasio')].category").value("Salud"));

        mockMvc.perform(delete("/api/rules/{id}", saved.getId()).with(auth())).andExpect(status().isOk());
        assertThat(ruleRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    void rulesAreListedAlphabeticallyByKeyword() throws Exception {
        mockMvc.perform(get("/api/rules").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].keyword").exists());
        var keywords = ruleRepository.findAllByOrderByKeywordAsc().stream().map(Rule::getKeyword).toList();
        assertThat(keywords).isSorted();
    }

    @Test
    void blankKeywordIsRejected() throws Exception {
        long before = ruleRepository.count();

        for (String blank : new String[]{"", "   "}) {
            mockMvc.perform(post("/api/rules").param("keyword", blank).param("category", "Ocio").with(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("La palabra clave no puede estar vacía"));
        }

        assertThat(ruleRepository.count()).isEqualTo(before);
    }

    @Test
    void deletingAnUnknownRuleIsHarmless() throws Exception {
        mockMvc.perform(delete("/api/rules/{id}", 999999).with(auth())).andExpect(status().isOk());
    }

    @Test
    void applyAllOnlyRecategorizesUncategorizedTransactions() throws Exception {
        Transaction pending = saveTx("2026-03-10", "COMPRA MERCADONA", -10, "Sin categorizar");
        Transaction noRule = saveTx("2026-03-10", "Transferencia a Pepe", -10, "Sin categorizar");
        Transaction manual = saveTx("2026-03-10", "COMPRA MERCADONA otra", -10, "Ocio");

        mockMvc.perform(post("/api/rules/apply-all").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        assertThat(transactionRepository.findById(pending.getId()).orElseThrow().getCategory()).isEqualTo("Supermercado");
        assertThat(transactionRepository.findById(noRule.getId()).orElseThrow().getCategory()).isEqualTo("Sin categorizar");
        assertThat(transactionRepository.findById(manual.getId()).orElseThrow().getCategory()).isEqualTo("Ocio");
    }

    // ---------- categories ----------

    @Test
    void categoryCanBeAddedAndDeleted() throws Exception {
        mockMvc.perform(post("/api/categories").param("name", "Mascotas").param("kind", "expense").with(auth()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/categories").with(auth()))
                .andExpect(jsonPath("$[?(@.name=='Mascotas')].kind").value("expense"));

        mockMvc.perform(delete("/api/categories/{name}", "Mascotas").with(auth())).andExpect(status().isOk());
        assertThat(categoryRepository.findByName("Mascotas")).isEmpty();
    }

    @Test
    void kindDefaultsToExpense() throws Exception {
        mockMvc.perform(post("/api/categories").param("name", "Sin tipo").with(auth())).andExpect(status().isOk());

        assertThat(categoryRepository.findByName("Sin tipo").orElseThrow().getKind()).isEqualTo("expense");
    }

    @Test
    void duplicateCategoryIs400() throws Exception {
        mockMvc.perform(post("/api/categories").param("name", "Ocio").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ya existe esa categoría"));
    }

    // ---- deleting categories ----

    @Test
    void sinCategorizarCannotBeDeleted() throws Exception {
        mockMvc.perform(delete("/api/categories/{name}", "Sin categorizar").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("No se puede eliminar la categoría «Sin categorizar»"));

        assertThat(categoryRepository.findByName("Sin categorizar")).isPresent();
    }

    @Test
    void deletingACategoryInUseMovesItsTransactionsBackToSinCategorizar() throws Exception {
        categoryRepository.save(category("Mascotas"));
        Transaction inUse = saveTx("2026-03-10", "Veterinario", -50, "Mascotas");
        Transaction other = saveTx("2026-03-11", "Cine", -10, "Ocio");

        mockMvc.perform(delete("/api/categories/{name}", "Mascotas").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.reassigned").value(1));

        assertThat(categoryRepository.findByName("Mascotas")).isEmpty();
        assertThat(transactionRepository.findById(inUse.getId()).orElseThrow().getCategory()).isEqualTo("Sin categorizar");
        assertThat(transactionRepository.findById(other.getId()).orElseThrow().getCategory()).isEqualTo("Ocio");
    }

    @Test
    void deletingACategoryAlsoDeletesItsRulesSoImportsCantAssignAGhostCategory() throws Exception {
        categoryRepository.save(category("Mascotas"));
        addRule("veterinario", "Mascotas");
        addRule("pienso", "Mascotas");
        long otherRules = ruleRepository.count();

        mockMvc.perform(delete("/api/categories/{name}", "Mascotas").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules_deleted").value(2));

        assertThat(ruleRepository.findAll()).noneMatch(r -> r.getCategory().equals("Mascotas"));
        assertThat(ruleRepository.count()).isEqualTo(otherRules - 2);
    }

    @Test
    void deletingAnUnusedCategoryReportsNothingReassigned() throws Exception {
        categoryRepository.save(category("Vacia"));

        mockMvc.perform(delete("/api/categories/{name}", "Vacia").with(auth()))
                .andExpect(jsonPath("$.reassigned").value(0))
                .andExpect(jsonPath("$.rules_deleted").value(0));
    }

    @Test
    void deletingAnUnknownCategoryIsHarmless() throws Exception {
        mockMvc.perform(delete("/api/categories/{name}", "NoExiste").with(auth())).andExpect(status().isOk());
    }

    // Tomcat rejects an encoded "/" (%2F) inside a path segment, so "Nómina / Ingresos" (a seeded category)
    // could not be deleted through /api/categories/{name}. The name travels as a query parameter instead.
    @Test
    void categoryNamesWithSlashesCanBeDeletedByQueryParameter() throws Exception {
        mockMvc.perform(delete("/api/categories").param("name", "Nómina / Ingresos").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertThat(categoryRepository.findByName("Nómina / Ingresos")).isEmpty();
    }

    @Test
    void theOldPathFormStillWorksForPlainNames() throws Exception {
        categoryRepository.save(category("Mascotas"));

        mockMvc.perform(delete("/api/categories/{name}", "Mascotas").with(auth())).andExpect(status().isOk());

        assertThat(categoryRepository.findByName("Mascotas")).isEmpty();
    }

    @Test
    void sinCategorizarIsProtectedThroughTheQueryParameterToo() throws Exception {
        mockMvc.perform(delete("/api/categories").param("name", "Sin categorizar").with(auth()))
                .andExpect(status().isBadRequest());

        assertThat(categoryRepository.findByName("Sin categorizar")).isPresent();
    }

    // ---- learning from a manual change: preview a rule, then create it and apply it ----

    @Test
    void previewSaysWhatARuleWouldDoWithoutCreatingIt() throws Exception {
        saveTx("2026-03-10", "BONPREU CENTRE", -30, "Ocio");
        saveTx("2026-03-11", "BONPREU ONLINE", -20, "Supermercado");
        saveTx("2026-03-12", "COMPRA BONPREU", -10, "Sin categorizar");
        saveTx("2026-03-13", "OTRA COSA", -5, "Ocio");
        long rulesBefore = ruleRepository.count();

        mockMvc.perform(get("/api/rules/preview").param("keyword", "bonpreu").param("category", "Supermercado").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches").value(3))
                .andExpect(jsonPath("$.already").value(1))
                .andExpect(jsonPath("$.would_change").value(2))
                .andExpect(jsonPath("$.from.Ocio").value(1))
                .andExpect(jsonPath("$['from']['Sin categorizar']").value(1))
                .andExpect(jsonPath("$.other_rule").value(0))
                .andExpect(jsonPath("$.existing_rule").doesNotExist());

        assertThat(ruleRepository.count()).isEqualTo(rulesBefore);
        assertThat(transactionRepository.findAll()).extracting(Transaction::getCategory)
                .containsExactlyInAnyOrder("Ocio", "Supermercado", "Sin categorizar", "Ocio");
    }

    @Test
    void previewCountsMovementsAMoreSpecificOrOlderRuleKeeps() throws Exception {
        // seeded: "uber eats" -> Restaurantes (more specific), "uber" -> Transporte (same keyword, older)
        saveTx("2026-03-10", "UBER EATS PEDIDO", -15, "Restaurantes");
        saveTx("2026-03-11", "UBER TRIP", -8, "Transporte");

        mockMvc.perform(get("/api/rules/preview").param("keyword", "uber").param("category", "Ocio").with(auth()))
                .andExpect(jsonPath("$.matches").value(2))
                .andExpect(jsonPath("$.would_change").value(0))
                .andExpect(jsonPath("$.other_rule").value(2))
                .andExpect(jsonPath("$.existing_rule").value("Transporte"));
    }

    @Test
    void creatingARuleWithApplyMovesTheMovementsItNowDecides() throws Exception {
        Transaction ocio = saveTx("2026-03-10", "BONPREU CENTRE", -30, "Ocio");
        Transaction pending = saveTx("2026-03-12", "COMPRA BONPREU", -10, "Sin categorizar");
        Transaction other = saveTx("2026-03-13", "OTRA COSA", -5, "Ocio");

        mockMvc.perform(post("/api/rules").param("keyword", "bonpreu").param("category", "Supermercado")
                        .param("apply", "true").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        assertThat(transactionRepository.findById(ocio.getId()).orElseThrow().getCategory()).isEqualTo("Supermercado");
        assertThat(transactionRepository.findById(pending.getId()).orElseThrow().getCategory()).isEqualTo("Supermercado");
        assertThat(transactionRepository.findById(other.getId()).orElseThrow().getCategory()).isEqualTo("Ocio");
        assertThat(ruleRepository.findAll()).extracting(Rule::getKeyword).contains("bonpreu");
    }

    @Test
    void creatingARuleWithoutApplyLeavesExistingMovementsAlone() throws Exception {
        Transaction ocio = saveTx("2026-03-10", "BONPREU CENTRE", -30, "Ocio");

        mockMvc.perform(post("/api/rules").param("keyword", "bonpreu").param("category", "Supermercado").with(auth()))
                .andExpect(jsonPath("$.updated").value(0));

        assertThat(transactionRepository.findById(ocio.getId()).orElseThrow().getCategory()).isEqualTo("Ocio");
    }

    @Test
    void previewOfABlankKeywordIsRejected() throws Exception {
        mockMvc.perform(get("/api/rules/preview").param("keyword", "  ").param("category", "Ocio").with(auth()))
                .andExpect(status().isBadRequest());
    }

    // ---- rule priority ----

    // "amazon" (Compras) is in the seed; a rule added LATER for "amazon fresh" has a higher id. With
    // first-row-wins it never fired. The most specific keyword must win.
    @Test
    void aMoreSpecificRuleAddedLaterWinsOnApplyAll() throws Exception {
        addRule("amazon fresh", "Supermercado");
        Transaction fresh = saveTx("2026-03-10", "AMAZON FRESH MADRID", -30, "Sin categorizar");
        Transaction plain = saveTx("2026-03-10", "AMAZON EU SARL", -20, "Sin categorizar");

        mockMvc.perform(post("/api/rules/apply-all").with(auth())).andExpect(status().isOk());

        assertThat(transactionRepository.findById(fresh.getId()).orElseThrow().getCategory()).isEqualTo("Supermercado");
        assertThat(transactionRepository.findById(plain.getId()).orElseThrow().getCategory()).isEqualTo("Compras");
    }

    @Test
    void aMoreSpecificRuleAddedLaterWinsOnImport() throws Exception {
        addRule("amazon fresh", "Supermercado");

        mockMvc.perform(multipart("/api/import/confirm")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "mov.csv", "text/csv",
                                "Fecha;Concepto;Importe\n15/03/2026;AMAZON FRESH MADRID;-30,00\n16/03/2026;AMAZON EU SARL;-20,00\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("account", "Principal")
                        .param("mapping", "{\"date\":\"Fecha\",\"description\":\"Concepto\",\"amount\":\"Importe\"}")
                        .with(auth()))
                .andExpect(status().isOk());

        assertThat(transactionRepository.findAll()).extracting(Transaction::getDescription, Transaction::getCategory)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("AMAZON FRESH MADRID", "Supermercado"),
                        org.assertj.core.groups.Tuple.tuple("AMAZON EU SARL", "Compras"));
    }

    @Test
    void seededDefaultsArePresent() throws Exception {
        mockMvc.perform(get("/api/categories").with(auth()))
                .andExpect(jsonPath("$[?(@.name=='Sin categorizar')]").exists())
                .andExpect(jsonPath("$[?(@.name=='Nómina / Ingresos')].kind").value("income"));
    }

    // ---------- password & backup ----------

    private String changePasswordBody(String current, String next) {
        return "{\"current_password\":\"%s\",\"new_password\":\"%s\"}".formatted(current, next);
    }

    @Test
    void changePasswordRejectsShortOrWrongPasswords() throws Exception {
        mockMvc.perform(post("/api/auth/change-password").with(auth()).contentType(APPLICATION_JSON)
                        .content(changePasswordBody(PASSWORD, "12345")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La contraseña nueva debe tener al menos 6 caracteres"));

        mockMvc.perform(post("/api/auth/change-password").with(auth()).contentType(APPLICATION_JSON)
                        .content(changePasswordBody("incorrecta", "nueva-123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La contraseña actual no es correcta"));

        // nothing changed
        mockMvc.perform(get("/api/auth/whoami").with(auth())).andExpect(status().isOk());
    }

    @Test
    void changePasswordSwitchesWhichCredentialsWork() throws Exception {
        mockMvc.perform(post("/api/auth/change-password").with(auth()).contentType(APPLICATION_JSON)
                        .content(changePasswordBody(PASSWORD, "nueva-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/auth/whoami").with(httpBasic("admin", "nueva-123"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/whoami").with(auth())).andExpect(status().isUnauthorized());
    }

    @Test
    void backupDownloadServesTheDatabaseFile() throws Exception {
        mockMvc.perform(get("/api/backup/download").with(auth()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("finanzas_backup_")))
                .andExpect(header().string("Content-Disposition", containsString(".db")));
    }
}
