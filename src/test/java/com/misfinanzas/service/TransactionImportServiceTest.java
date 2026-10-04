package com.misfinanzas.service;

import com.misfinanzas.dto.ImportRow;
import com.misfinanzas.entity.Rule;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.RuleRepository;
import com.misfinanzas.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.misfinanzas.TestMoney.eur;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TransactionImportService is the dedupe/categorization/save logic shared by a manual file import
 * (ImportController) and the bank-sync import (BankSyncController) — most of it is already exercised
 * indirectly through ImportApiTest, but these cover it directly at the service level, independent of
 * how the ImportRows were built.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TransactionImportService.class, ImportService.class, CategorizationService.class})
class TransactionImportServiceTest {

    @Autowired
    TransactionImportService service;
    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    RuleRepository ruleRepository;

    private void rule(String keyword, String category) {
        Rule r = new Rule();
        r.setKeyword(keyword);
        r.setCategory(category);
        ruleRepository.saveAndFlush(r);
    }

    @Test
    void newRowsAreCategorizedAccountedAndSaved() {
        rule("mercadona", "Supermercado");

        TransactionImportService.ImportResult result = service.persist(List.of(
                new ImportRow(LocalDate.parse("2026-03-15"), "COMPRA MERCADONA", eur(-45.30), "Principal", null)));

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(0);
        Transaction saved = transactionRepository.findAll().get(0);
        assertThat(saved.getCategory()).isEqualTo("Supermercado");
        assertThat(saved.getAccountingMonth()).isEqualTo("2026-03");
        assertThat(saved.getAccount()).isEqualTo("Principal");
    }

    @Test
    void uncategorizedRowsFallBackToTheDefaultCategory() {
        TransactionImportService.ImportResult result = service.persist(List.of(
                new ImportRow(LocalDate.parse("2026-03-15"), "Transferencia a Pepe", eur(-20.0), "Principal", null)));

        assertThat(result.added()).isEqualTo(1);
        assertThat(transactionRepository.findAll().get(0).getCategory()).isEqualTo("Sin categorizar");
    }

    @Test
    void reimportingTheSameRowIsADuplicateNotANewRow() {
        ImportRow row = new ImportRow(LocalDate.parse("2026-03-15"), "NOMINA", eur(1500.0), "Principal", null);
        service.persist(List.of(row));

        TransactionImportService.ImportResult result = service.persist(List.of(row));

        assertThat(result.added()).isEqualTo(0);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(transactionRepository.findAll()).hasSize(1);
    }

    @Test
    void duplicateFillsMissingBalanceButKeepsTheExistingCategory() {
        service.persist(List.of(new ImportRow(LocalDate.parse("2026-03-15"), "NOMINA", eur(1500.0), "Principal", null)));
        Transaction stored = transactionRepository.findAll().get(0);
        stored.setCategory("Ingresos");
        transactionRepository.saveAndFlush(stored);

        TransactionImportService.ImportResult result = service.persist(List.of(
                new ImportRow(LocalDate.parse("2026-03-15"), "NOMINA", eur(1500.0), "Principal", eur(2500.0))));

        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.balancesFilled()).isEqualTo(1);
        Transaction after = transactionRepository.findAll().get(0);
        assertThat(after.getBalance()).isEqualTo(eur(2500.0));
        assertThat(after.getCategory()).isEqualTo("Ingresos");
    }

    @Test
    void twoIdenticalRowsInTheSameBatchAreBothAdded() {
        ImportRow coffee = new ImportRow(LocalDate.parse("2026-03-15"), "CAFETERIA", eur(-1.5), "Principal", null);

        TransactionImportService.ImportResult result = service.persist(List.of(coffee, coffee));

        assertThat(result.added()).isEqualTo(2);
        assertThat(transactionRepository.findAll()).hasSize(2);
    }

    @Test
    void aBankMovementStoredWithItsRawDescriptionIsRecognisedOnceDescriptionsAreCleaned() {
        String raw = "BIZUM // OTROS // ENVIADO: Sin concepto";
        service.persist(List.of(new ImportRow(LocalDate.parse("2026-09-11"), raw, eur(-169.0), "Principal", null)));

        // Same movement downloaded again, now cleaned by EnableBankingClient, which offers the raw text too.
        TransactionImportService.ImportResult result = service.persist(List.of(new ImportRow(
                LocalDate.parse("2026-09-11"), "Bizum — ENVIADO: Sin concepto", eur(-169.0), "Principal", eur(4383.61),
                Map.of("bank-raw", raw))));

        assertThat(result.added()).isEqualTo(0);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.balancesFilled()).isEqualTo(1);
        assertThat(transactionRepository.findAll()).hasSize(1);
    }

    @Test
    void aCardPaymentStoredWithTheCityGluedOnIsRecognisedOnceTheMerchantIsReadProperly() {
        // What the first cleaning stored for this movement, before merchants were read from their fixed-width field.
        service.persist(List.of(new ImportRow(LocalDate.parse("2026-06-22"),
                "Talleres mecanicos perez madrid — Pago con tarjeta", eur(-30.0), "Principal", null)));

        String raw = "PAGO CON TARJETA DE SERVICIOS VARIOS // PAGO CON TARJETA // "
                + String.format("%-25s%-13s%s", "TALLERES MECANICOS PEREZ", "MADRID", "ES");
        TransactionImportService.ImportResult result = service.persist(List.of(EnableBankingClient.toImportRow(
                new EnableBankingClient.BankTransaction("2026-06-22", null, null, new EnableBankingClient.Amount("EUR", "30.00"),
                        "DBIT", List.of(raw), null, "BOOK"), "Principal")));

        assertThat(result.added()).isEqualTo(0);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(transactionRepository.findAll()).hasSize(1);
    }
}
