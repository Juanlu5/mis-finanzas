package com.misfinanzas.web;

import com.misfinanzas.entity.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.misfinanzas.TestMoney.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImportApiTest extends ApiTestBase {

    // Spying replaces the TransactionRepository bean for the whole context (so the inherited
    // `transactionRepository` field above resolves to the same spy), letting one specific save() fail
    // without touching how the others behave.
    @MockitoSpyBean
    private com.misfinanzas.repository.TransactionRepository transactionRepositorySpy;

    private static final String MAPPING = "{\"date\":\"Fecha\",\"description\":\"Concepto\",\"amount\":\"Importe\"}";
    private static final String MAPPING_WITH_BALANCE =
            "{\"date\":\"Fecha\",\"description\":\"Concepto\",\"amount\":\"Importe\",\"balance\":\"Saldo\"}";

    private static final String STATEMENT = """
            Fecha;Concepto;Importe
            15/03/2026;COMPRA MERCADONA 1;-45,30
            16/03/2026;NOMINA EMPRESA;1500,00
            17/03/2026;Transferencia a Pepe;-20,00
            ;;
            xx;fila con fecha mala;-1,00
            """;

    private static final String STATEMENT_WITH_BALANCE = """
            Fecha;Concepto;Importe;Saldo
            15/03/2026;COMPRA MERCADONA 1;-45,30;954,70
            16/03/2026;NOMINA EMPRESA;1500,00;2454,70
            17/03/2026;Transferencia a Pepe;-20,00;2434,70
            """;

    private ResultActions confirm(String csv, String account, String mapping) throws Exception {
        return mockMvc.perform(multipart("/api/import/confirm")
                .file(new MockMultipartFile("file", "mov.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .param("account", account)
                .param("mapping", mapping)
                .with(auth()));
    }

    private List<Transaction> all() {
        return transactionRepository.findAll();
    }

    @Test
    void previewGuessesTheMapping() throws Exception {
        mockMvc.perform(multipart("/api/import/preview")
                        .file(new MockMultipartFile("file", "mov.csv", "text/csv", STATEMENT.getBytes(StandardCharsets.UTF_8)))
                        .with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(3))
                .andExpect(jsonPath("$.mapping.date").value("Fecha"))
                .andExpect(jsonPath("$.mapping.description").value("Concepto"))
                .andExpect(jsonPath("$.mapping.amount").value("Importe"))
                .andExpect(jsonPath("$.rows").value(4))
                .andExpect(jsonPath("$.sample.length()").value(4));
    }

    @Test
    void previewOfAnUnreadableFileIs400NotAServerError() throws Exception {
        mockMvc.perform(multipart("/api/import/preview")
                        .file(new MockMultipartFile("file", "roto.xlsx", "application/octet-stream", "no soy excel".getBytes()))
                        .with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void confirmImportsCategorizesAndCountsEverything() throws Exception {
        confirm(STATEMENT, "Principal", MAPPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(3))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.total_rows").value(4))
                .andExpect(jsonPath("$.balances_filled").value(0));

        assertThat(all()).extracting(Transaction::getDescription, Transaction::getCategory, Transaction::getAmount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("COMPRA MERCADONA 1", "Supermercado", eur(-45.30)),
                        org.assertj.core.groups.Tuple.tuple("NOMINA EMPRESA", "Nómina / Ingresos", eur(1500.0)),
                        org.assertj.core.groups.Tuple.tuple("Transferencia a Pepe", "Sin categorizar", eur(-20.0)));
        assertThat(all()).allSatisfy(t -> {
            assertThat(t.getAccount()).isEqualTo("Principal");
            assertThat(t.getAccountingMonth()).isEqualTo("2026-03");
            assertThat(t.getBalance()).isNull();
        });
    }

    @Test
    void reimportingTheSameFileIsIdempotent() throws Exception {
        confirm(STATEMENT, "Principal", MAPPING).andExpect(jsonPath("$.added").value(3));

        confirm(STATEMENT, "Principal", MAPPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.duplicates").value(3));

        assertThat(all()).hasSize(3);
    }

    @Test
    void sameStatementInAnotherAccountIsNotADuplicate() throws Exception {
        confirm(STATEMENT, "Principal", MAPPING);

        confirm(STATEMENT, "Ahorro", MAPPING)
                .andExpect(jsonPath("$.added").value(3))
                .andExpect(jsonPath("$.duplicates").value(0));

        assertThat(all()).hasSize(6);
    }

    @Test
    void duplicateFillsMissingBalanceButKeepsCategoryAndDate() throws Exception {
        confirm(STATEMENT, "Principal", MAPPING);
        Transaction mercadona = all().stream().filter(t -> t.getDescription().contains("MERCADONA")).findFirst().orElseThrow();
        mockMvc.perform(patch("/api/transactions/{id}", mercadona.getId()).with(auth())
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .content("category=Ocio&accounting_month=2026-02"))
                .andExpect(status().isOk());

        confirm(STATEMENT_WITH_BALANCE, "Principal", MAPPING_WITH_BALANCE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.duplicates").value(3))
                .andExpect(jsonPath("$.balances_filled").value(3));

        Transaction after = transactionRepository.findById(mercadona.getId()).orElseThrow();
        assertThat(after.getBalance()).isEqualTo(eur(954.70));
        assertThat(after.getCategory()).isEqualTo("Ocio");
        assertThat(after.getAccountingMonth()).isEqualTo("2026-02");
        assertThat(after.getDate().toString()).isEqualTo("2026-03-15");
    }

    @Test
    void existingBalanceIsNeverOverwritten() throws Exception {
        confirm(STATEMENT_WITH_BALANCE, "Principal", MAPPING_WITH_BALANCE);
        String changedBalances = STATEMENT_WITH_BALANCE.replace("954,70", "1,00");

        confirm(changedBalances, "Principal", MAPPING_WITH_BALANCE)
                .andExpect(jsonPath("$.duplicates").value(3))
                .andExpect(jsonPath("$.balances_filled").value(0));

        assertThat(all()).extracting(Transaction::getBalance).contains(eur(954.70)).doesNotContain(eur(1.0));
    }

    @Test
    void importedTransactionsShowUpUnderTheirAccountingMonth() throws Exception {
        confirm(STATEMENT, "Principal", MAPPING);

        mockMvc.perform(get("/api/transactions").param("month", "2026-03").with(auth()))
                .andExpect(jsonPath("$.total").value(3));
        mockMvc.perform(get("/api/summary").param("month", "2026-03").with(auth()))
                .andExpect(jsonPath("$.income").value(1500.0))
                .andExpect(jsonPath("$.expense").value(65.3))
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void invalidMappingJsonIs400() throws Exception {
        confirm(STATEMENT, "Principal", "esto no es json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mapeo de columnas inválido"));

        assertThat(all()).isEmpty();
    }

    @Test
    void mappingToColumnsThatDontExistImportsNothing() throws Exception {
        confirm(STATEMENT, "Principal", "{\"date\":\"NoExiste\",\"description\":\"Concepto\",\"amount\":\"Importe\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.skipped").value(4));
    }

    // Two genuinely different purchases on the same day with the same concept and amount (two coffees at the
    // same cafe) produce the same dedupe key, so the second one is dropped as a "duplicate" and never saved.
    @Test
    void twoIdenticalPurchasesInTheSameFileAreBothImported() throws Exception {
        String csv = """
                Fecha;Concepto;Importe
                15/03/2026;CAFETERIA CENTRAL;-1,50
                15/03/2026;CAFETERIA CENTRAL;-1,50
                """;

        confirm(csv, "Principal", MAPPING)
                .andExpect(jsonPath("$.added").value(2))
                .andExpect(jsonPath("$.duplicates").value(0));

        assertThat(all()).hasSize(2);
    }

    @Test
    void reimportingIdenticalPurchasesIsStillIdempotent() throws Exception {
        String csv = """
                Fecha;Concepto;Importe
                15/03/2026;CAFETERIA CENTRAL;-1,50
                15/03/2026;CAFETERIA CENTRAL;-1,50
                15/03/2026;CAFETERIA CENTRAL;-1,50
                16/03/2026;LIDL;-9,00
                """;
        confirm(csv, "Principal", MAPPING).andExpect(jsonPath("$.added").value(4));

        confirm(csv, "Principal", MAPPING)
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.duplicates").value(4));

        assertThat(all()).hasSize(4);
    }

    @Test
    void identicalPurchasesAlreadyStoredWithTheOldKeyAreNotDuplicatedOnReimport() throws Exception {
        // A database created before occurrence numbering holds one row per (date, concept, amount), under the plain key.
        Transaction legacy = saveTx("2026-03-15", "CAFETERIA CENTRAL", -1.5, "Restaurantes");
        legacy.setDedupeKey(new com.misfinanzas.service.ImportService()
                .makeDedupeKey("Principal", legacy.getDate(), "CAFETERIA CENTRAL", eur(-1.5)));
        transactionRepository.saveAndFlush(legacy);

        confirm("""
                Fecha;Concepto;Importe
                15/03/2026;CAFETERIA CENTRAL;-1,50
                15/03/2026;CAFETERIA CENTRAL;-1,50
                """, "Principal", MAPPING)
                .andExpect(jsonPath("$.added").value(1))      // only the second coffee was missing
                .andExpect(jsonPath("$.duplicates").value(1));

        assertThat(all()).hasSize(2);
        assertThat(transactionRepository.findById(legacy.getId()).orElseThrow().getCategory()).isEqualTo("Restaurantes");
    }

    // ---- The dedupe key must not depend on which optional "detail" column was chosen ----
    //
    // The stored description is the description column plus, when chosen, the detail column
    // ("COMPRA MERCADONA 1 — Compra con tarjeta"), and the dedupe key hashes it. So importing the same
    // statement again with another choice of detail column (or after the column guessing improved) used to
    // build a different description and re-add every row instead of recognising it.

    private static final String DETAILED = """
            Fecha;Concepto;Movimiento;Divisa;Importe
            15/03/2026;COMPRA MERCADONA 1;Compra con tarjeta;EUR;-45,30
            16/03/2026;NOMINA EMPRESA;Transferencia recibida;EUR;1500,00
            17/03/2026;Transferencia a Pepe;Bizum;EUR;-20,00
            """;
    private static final String NO_DETAIL =
            "{\"date\":\"Fecha\",\"description\":\"Concepto\",\"amount\":\"Importe\"}";
    private static final String DETAIL_MOVIMIENTO =
            "{\"date\":\"Fecha\",\"description\":\"Concepto\",\"description_extra\":\"Movimiento\",\"amount\":\"Importe\"}";
    private static final String DETAIL_DIVISA =
            "{\"date\":\"Fecha\",\"description\":\"Concepto\",\"description_extra\":\"Divisa\",\"amount\":\"Importe\"}";

    private void assertAllDuplicates(ResultActions result, int rows) throws Exception {
        result.andExpect(jsonPath("$.added").value(0)).andExpect(jsonPath("$.duplicates").value(rows));
    }

    @Test
    void reimportingWithoutTheDetailColumnRecognisesRowsImportedWithIt() throws Exception {
        confirm(DETAILED, "Principal", DETAIL_MOVIMIENTO).andExpect(jsonPath("$.added").value(3));

        assertAllDuplicates(confirm(DETAILED, "Principal", NO_DETAIL), 3);

        assertThat(all()).hasSize(3);
    }

    @Test
    void reimportingWithTheDetailColumnRecognisesRowsImportedWithoutIt() throws Exception {
        confirm(DETAILED, "Principal", NO_DETAIL).andExpect(jsonPath("$.added").value(3));

        assertAllDuplicates(confirm(DETAILED, "Principal", DETAIL_MOVIMIENTO), 3);

        assertThat(all()).hasSize(3);
    }

    @Test
    void reimportingWithAnotherDetailColumnRecognisesTheSameRows() throws Exception {
        confirm(DETAILED, "Principal", DETAIL_MOVIMIENTO).andExpect(jsonPath("$.added").value(3));

        assertAllDuplicates(confirm(DETAILED, "Principal", DETAIL_DIVISA), 3);

        assertThat(all()).hasSize(3);
    }

    @Test
    void aNewRowInAnOverlappingStatementIsStillImportedWhateverTheMapping() throws Exception {
        confirm(DETAILED, "Principal", DETAIL_MOVIMIENTO);

        confirm(DETAILED + "18/03/2026;LIDL;Compra con tarjeta;EUR;-9,00\n", "Principal", NO_DETAIL)
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.duplicates").value(3));

        assertThat(all()).hasSize(4);
    }

    @Test
    void rowsThatDifferOnlyInTheDetailAreKeptApartAndStayRecognisedWithoutIt() throws Exception {
        String twoPurchases = """
                Fecha;Concepto;Movimiento;Divisa;Importe
                15/03/2026;AMAZON;Compra con tarjeta;EUR;-20,00
                15/03/2026;AMAZON;Compra online;EUR;-20,00
                """;
        confirm(twoPurchases, "Principal", DETAIL_MOVIMIENTO).andExpect(jsonPath("$.added").value(2));

        // without the detail both rows look identical, and must still map onto the two stored ones
        assertAllDuplicates(confirm(twoPurchases, "Principal", NO_DETAIL), 2);

        assertThat(all()).hasSize(2);
    }

    @Test
    void identicalPurchasesImportedWithTheDetailAreStillRecognisedWithoutIt() throws Exception {
        String twoCoffees = """
                Fecha;Concepto;Movimiento;Divisa;Importe
                15/03/2026;CAFETERIA CENTRAL;Compra con tarjeta;EUR;-1,50
                15/03/2026;CAFETERIA CENTRAL;Compra con tarjeta;EUR;-1,50
                """;
        confirm(twoCoffees, "Principal", DETAIL_MOVIMIENTO).andExpect(jsonPath("$.added").value(2));

        assertAllDuplicates(confirm(twoCoffees, "Principal", NO_DETAIL), 2);

        assertThat(all()).hasSize(2);
    }

    @Test
    void aSecondCoffeeIsStillImportedWhenOnlyOneWasStoredUnderAnotherMapping() throws Exception {
        confirm("""
                Fecha;Concepto;Movimiento;Divisa;Importe
                15/03/2026;CAFETERIA CENTRAL;Compra con tarjeta;EUR;-1,50
                """, "Principal", DETAIL_MOVIMIENTO).andExpect(jsonPath("$.added").value(1));

        confirm("""
                Fecha;Concepto;Movimiento;Divisa;Importe
                15/03/2026;CAFETERIA CENTRAL;Compra con tarjeta;EUR;-1,50
                15/03/2026;CAFETERIA CENTRAL;Compra con tarjeta;EUR;-1,50
                """, "Principal", NO_DETAIL)
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.duplicates").value(1));

        assertThat(all()).hasSize(2);
    }

    @Test
    void anotherAccountIsStillNotADuplicateWhateverTheMapping() throws Exception {
        confirm(DETAILED, "Principal", DETAIL_MOVIMIENTO);

        confirm(DETAILED, "Ahorro", NO_DETAIL).andExpect(jsonPath("$.added").value(3));

        assertThat(all()).hasSize(6);
    }

    @Test
    void aDifferentAmountOrDateIsNotMistakenForADuplicate() throws Exception {
        confirm(DETAILED, "Principal", DETAIL_MOVIMIENTO);

        confirm("""
                Fecha;Concepto;Movimiento;Divisa;Importe
                15/03/2026;COMPRA MERCADONA 1;Compra con tarjeta;EUR;-45,31
                18/03/2026;NOMINA EMPRESA;Transferencia recibida;EUR;1500,00
                """, "Principal", NO_DETAIL)
                .andExpect(jsonPath("$.added").value(2))
                .andExpect(jsonPath("$.duplicates").value(0));
    }

    @Test
    void theBalanceIsFilledInEvenWhenTheRowIsRecognisedThroughAnotherMapping() throws Exception {
        confirm(DETAILED, "Principal", DETAIL_MOVIMIENTO).andExpect(jsonPath("$.added").value(3));

        confirm("""
                Fecha;Concepto;Movimiento;Divisa;Importe;Saldo
                15/03/2026;COMPRA MERCADONA 1;Compra con tarjeta;EUR;-45,30;954,70
                16/03/2026;NOMINA EMPRESA;Transferencia recibida;EUR;1500,00;2454,70
                17/03/2026;Transferencia a Pepe;Bizum;EUR;-20,00;2434,70
                """, "Principal",
                "{\"date\":\"Fecha\",\"description\":\"Concepto\",\"amount\":\"Importe\",\"balance\":\"Saldo\"}")
                .andExpect(jsonPath("$.duplicates").value(3))
                .andExpect(jsonPath("$.balances_filled").value(3));
    }

    @Test
    void theRowIsStoredWithTheDescriptionOfTheMappingUsedNotTheAlternativeOnes() throws Exception {
        confirm(DETAILED, "Principal", DETAIL_MOVIMIENTO);

        assertThat(all()).extracting(Transaction::getDescription)
                .contains("COMPRA MERCADONA 1 — Compra con tarjeta");
    }

    // ---- Excel through /confirm (all the other import tests use CSV) ----

    private byte[] excelStatement(short dateFormat) throws Exception {
        return excelStatement(new org.apache.poi.xssf.usermodel.XSSFWorkbook(), dateFormat);
    }

    private byte[] excelStatement(org.apache.poi.ss.usermodel.Workbook wb, short dateFormat) throws Exception {
        try (wb; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Movimientos");
            sheet.createRow(0).createCell(0).setCellValue("Movimientos de la cuenta");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("Fecha");
            header.createCell(1).setCellValue("Concepto");
            header.createCell(2).setCellValue("Importe");
            var style = wb.createCellStyle();
            style.setDataFormat(dateFormat);
            Object[][] rows = {
                    {java.time.LocalDate.of(2026, 3, 15), "COMPRA MERCADONA 1", -45.3},
                    {java.time.LocalDate.of(2026, 4, 3), "NOMINA EMPRESA", 1500.0},   // day <= 12: ambiguous d/m vs m/d
            };
            for (int i = 0; i < rows.length; i++) {
                var r = sheet.createRow(2 + i);
                var d = r.createCell(0);
                d.setCellValue((java.time.LocalDate) rows[i][0]);
                d.setCellStyle(style);
                r.createCell(1).setCellValue((String) rows[i][1]);
                r.createCell(2).setCellValue((Double) rows[i][2]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void excelWithTheDefaultShortDateFormatImportsWithTheRightDates() throws Exception {
        mockMvc.perform(multipart("/api/import/confirm")
                        .file(new MockMultipartFile("file", "mov.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelStatement((short) 14)))
                        .param("account", "Principal")
                        .param("mapping", MAPPING)
                        .with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        assertThat(all()).extracting(t -> t.getDate().toString(), Transaction::getDescription, Transaction::getCategory)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("2026-03-15", "COMPRA MERCADONA 1", "Supermercado"),
                        org.assertj.core.groups.Tuple.tuple("2026-04-03", "NOMINA EMPRESA", "Nómina / Ingresos"));
    }

    @Test
    void legacyXlsImportsThroughConfirmToo() throws Exception {
        byte[] xls = excelStatement(new org.apache.poi.hssf.usermodel.HSSFWorkbook(), (short) 14);

        mockMvc.perform(multipart("/api/import/confirm")
                        .file(new MockMultipartFile("file", "mov.xls", "application/vnd.ms-excel", xls))
                        .param("account", "Principal").param("mapping", MAPPING).with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        assertThat(all()).extracting(t -> t.getDate().toString()).containsExactlyInAnyOrder("2026-03-15", "2026-04-03");
    }

    @Test
    void reimportingTheSameExcelIsIdempotent() throws Exception {
        byte[] xlsx = excelStatement((short) 14);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(multipart("/api/import/confirm")
                            .file(new MockMultipartFile("file", "mov.xlsx", "application/octet-stream", xlsx))
                            .param("account", "Principal").param("mapping", MAPPING).with(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.added").value(i == 0 ? 2 : 0))
                    .andExpect(jsonPath("$.duplicates").value(i == 0 ? 0 : 2));
        }
        assertThat(all()).hasSize(2);
    }

    // ---- POST /api/import/confirm is one transaction: a failure halfway through must not leave a
    // half-imported file behind ----
    //
    // ApiTestBase normally wraps every test in a transaction it rolls back afterwards, so a nested
    // @Transactional on the controller just joins that outer transaction and a failure only marks it
    // rollback-only — the earlier, already-sent inserts would still be visible to a query run right after,
    // even though they'll never really commit. Opting this one test out of that wrapper (NOT_SUPPORTED)
    // makes the controller's @Transactional the real, physical transaction, so its rollback is real and
    // observable too.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailureMidImportLeavesNothingStored() throws Exception {
        doThrow(new RuntimeException("boom"))
                .when(transactionRepositorySpy)
                .save(org.mockito.ArgumentMatchers.<Transaction>argThat(t -> "BBB".equals(t.getDescription())));

        String csv = """
                Fecha;Concepto;Importe
                15/03/2026;AAA;-1,00
                16/03/2026;BBB;-2,00
                """;

        assertThatThrownBy(() -> confirm(csv, "Principal", MAPPING)).hasRootCauseMessage("boom");

        assertThat(all()).isEmpty();
    }
}
