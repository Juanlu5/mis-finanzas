package com.misfinanzas.service;

import com.misfinanzas.dto.ImportRow;
import com.misfinanzas.dto.ParsedTable;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

import static com.misfinanzas.TestMoney.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportServiceTest {

    private final ImportService service = new ImportService();

    // ---------- helpers ----------

    private static byte[] bytes(String text, Charset charset) {
        return text.getBytes(charset);
    }

    private static byte[] utf8(String text) {
        return bytes(text, StandardCharsets.UTF_8);
    }

    private static Map<String, String> row(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // ---------- normalizeAmount ----------

    @Nested
    class NormalizeAmount {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource(delimiter = '|', value = {
                "1.234,56      | 1234.56",     // ES: dot thousands, comma decimals
                "1,234.56      | 1234.56",     // EN: comma thousands, dot decimals
                "1.234.567,89  | 1234567.89",
                "2600,1        | 2600.1",      // trailing-zero cent dropped by the bank
                "2600,10       | 2600.1",
                "-45,30        | -45.3",
                "+100          | 100.0",
                "12,50 €       | 12.5",
                "$ 12.50       | 12.5",
                "1,234         | 1234.0",      // 3 digits after a lone comma = thousands
                "12.5          | 12.5",
                "12.50         | 12.5",
                "1.234         | 1234.0",      // lone dot + 3 digits = Spanish thousands
                "-1.234        | -1234.0",
                "1.234.567     | 1234567.0",
                "0.125         | 0.125",       // leading zero: a decimal fraction, not thousands
                "1.2345        | 1.2345",
                "0             | 0.0",
        })
        void parsesKnownLocaleFormats(String input, double expected) {
            assertThat(service.normalizeAmount(input)).isEqualByComparingTo(BigDecimal.valueOf(expected));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "nan", "NaN", "abc", "15/03/2026", "12,5,5x"})
        void returnsNullWhenNotANumber(String input) {
            assertThat(service.normalizeAmount(input)).isNull();
        }

        // A lone dot followed by exactly 3 digits is a thousands separator in
        // a Spanish export ("1.234" = 1234), but Double.parseDouble reads it as 1.234.
        @Test
        void dotAsThousandsSeparatorWithoutDecimals() {
            assertThat(service.normalizeAmount("1.234")).isEqualByComparingTo(BigDecimal.valueOf(1234.0));
        }

        // Excel/bank exports often put a non-breaking space between number and €;
        // String.trim() and replace(" ", "") don't remove U+00A0.
        @Test
        void toleratesNonBreakingSpace() {
            assertThat(service.normalizeAmount("1.234,56 €")).isEqualByComparingTo(BigDecimal.valueOf(1234.56));
        }
    }

    // ---------- normalizeDate ----------

    @Nested
    class NormalizeDate {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource(delimiter = '|', value = {
                "15/03/2026 | 2026-03-15",
                "15-03-2026 | 2026-03-15",
                "2026-03-15 | 2026-03-15",
                "15.03.2026 | 2026-03-15",
                "15/03/26   | 2026-03-15",   // two-digit year
                "03/13/2026 | 2026-03-13",   // month/day fallback, only when dd/MM is impossible
                " 15/03/2026 | 2026-03-15",  // surrounding whitespace
        })
        void parsesSupportedFormats(String input, LocalDate expected) {
            assertThat(service.normalizeDate(input)).isEqualTo(expected);
        }

        // The default SMART resolver clamps a day that doesn't exist in the month (31/02 -> 28/02),
        // silently importing the transaction on the wrong date. It should be rejected instead.
        @ParameterizedTest(name = "\"{0}\" is rejected")
        @ValueSource(strings = {
                "31/02/2026", "30/02/2026", "29/02/2026",   // 2026 is not a leap year
                "31/04/2026", "31/06/2026", "32/01/2026", "00/03/2026",
                "2026-02-31", "31-02-2026", "31.02.2026", "31/02/26",
                "02/31/2026"                                  // month/day fallback must not clamp either
        })
        void impossibleCalendarDatesAreRejectedNotClamped(String input) {
            assertThat(service.normalizeDate(input)).isNull();
        }

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource(delimiter = '|', value = {
                "29/02/2024 | 2024-02-29",   // leap day is valid
                "31/01/2026 | 2026-01-31",
                "30/04/2026 | 2026-04-30",
                "28/02/2026 | 2026-02-28",
                "29/02/28   | 2028-02-29",   // two-digit year, leap
        })
        void lastValidDaysOfTheMonthAreAccepted(String input, LocalDate expected) {
            assertThat(service.normalizeDate(input)).isEqualTo(expected);
        }

        @Test
        void rowWithAnImpossibleDateIsCountedAsAnErrorInsteadOfShiftedToAnotherDay() {
            ParsedTable table = service.readAny("mov.csv", utf8("Fecha;Concepto;Importe\n31/02/2026;Mercadona;-1,00\n"));

            ImportService.BuildResult result = service.buildRows(table, service.guessMapping(table.columns()), "Principal");

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).isEqualTo(1);
        }

        @Test
        void ambiguousDateIsReadAsDayFirst() {
            assertThat(service.normalizeDate("03/04/2026")).isEqualTo(LocalDate.of(2026, 4, 3));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "NaT", "nat", "hola", "2026/03/15", "15 marzo 2026"})
        void returnsNullWhenUnparseable(String input) {
            assertThat(service.normalizeDate(input)).isNull();
        }
    }

    // ---------- makeDedupeKey ----------

    @Nested
    class DedupeKey {

        private final LocalDate date = LocalDate.of(2026, 3, 15);

        @Test
        void isDeterministicSha256Hex() {
            String key = service.makeDedupeKey("Principal", date, "Mercadona", eur(-45.30));
            assertThat(key).matches("[0-9a-f]{64}");
            assertThat(service.makeDedupeKey("Principal", date, "Mercadona", eur(-45.30))).isEqualTo(key);
        }

        @Test
        void ignoresCaseAndSurroundingWhitespaceInDescription() {
            assertThat(service.makeDedupeKey("Principal", date, "  MERCADONA ", eur(-45.30)))
                    .isEqualTo(service.makeDedupeKey("Principal", date, "mercadona", eur(-45.30)));
        }

        @Test
        void roundsAmountToCents() {
            assertThat(service.makeDedupeKey("Principal", date, "x", new BigDecimal("12.499999")))
                    .isEqualTo(service.makeDedupeKey("Principal", date, "x", new BigDecimal("12.50")));
        }

        /**
         * Keys computed by the code from before amounts were BigDecimals (a double, written as Java writes doubles).
         * Every stored row has a key like these: if one of them changed, the next import or bank download would
         * store every movement again. How many decimals the BigDecimal carries must not matter either.
         */
        @Test
        void keysAreExactlyTheOnesStoredWhenAmountsWereDoubles() {
            assertThat(service.makeDedupeKey("Principal", date, "Mercadona", new BigDecimal("-45.30")))
                    .isEqualTo("97626c1c8049f90c0d942e806293a6e375142c36b08e5a1006be40ac9d970bb3"); // "...|-45.3"
            assertThat(service.makeDedupeKey("Principal", date, "Mercadona", new BigDecimal("-45.3")))
                    .isEqualTo("97626c1c8049f90c0d942e806293a6e375142c36b08e5a1006be40ac9d970bb3");
            assertThat(service.makeDedupeKey("Principal", date, "Nómina", new BigDecimal("1700.00")))
                    .isEqualTo("7e9c0161f830d7e9df4300782c0d6a7ceb41da6740944a2b912ebcade2f67cd3"); // "...|1700.0"
            assertThat(service.makeDedupeKey("Principal", date, "Nómina", new BigDecimal("1700")))
                    .isEqualTo("7e9c0161f830d7e9df4300782c0d6a7ceb41da6740944a2b912ebcade2f67cd3");
            assertThat(service.makeDedupeKey("Principal", date, "Café", new BigDecimal("-0.29"), 1))
                    .isEqualTo("4aa8a8af5885c9fc797d611a5cd11af5a987a3ec518ad93792e8f062af572872"); // "...|-0.29|#1"
            assertThat(service.makeDedupeKey("Principal", date, "Premio", new BigDecimal("12000000.00")))
                    .isEqualTo("f419873e5f5137f10dda80a683a52e98a6cda3b3f989eb3cc6e62cca09a990e3"); // "...|1.2E7"
            assertThat(service.makeDedupeKey("Principal", date, "Raro", new BigDecimal("-12.345")))
                    .isEqualTo("6765ff461c2b22396a7d5e54b63affa23eba9b4ed5cc9cd77547afef36698543"); // "...|-12.34"
        }

        @Test
        void firstOccurrenceKeepsTheOriginalKeySoStoredRowsStillMatch() {
            assertThat(service.makeDedupeKey("Principal", date, "Mercadona", eur(-45.30), 0))
                    .isEqualTo(service.makeDedupeKey("Principal", date, "Mercadona", eur(-45.30)));
        }

        @Test
        void eachOccurrenceGetsItsOwnKey() {
            String first = service.makeDedupeKey("Principal", date, "Cafe", eur(-1.5), 0);
            String second = service.makeDedupeKey("Principal", date, "Cafe", eur(-1.5), 1);
            String third = service.makeDedupeKey("Principal", date, "Cafe", eur(-1.5), 2);

            assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
            assertThat(service.makeDedupeKey("Principal", date, "Cafe", eur(-1.5), 1)).isEqualTo(second);
        }

        @Test
        void changesWhenAnyFieldChanges() {
            String base = service.makeDedupeKey("Principal", date, "Mercadona", eur(-45.30));
            assertThat(service.makeDedupeKey("Ahorro", date, "Mercadona", eur(-45.30))).isNotEqualTo(base);
            assertThat(service.makeDedupeKey("Principal", date.plusDays(1), "Mercadona", eur(-45.30))).isNotEqualTo(base);
            assertThat(service.makeDedupeKey("Principal", date, "Lidl", eur(-45.30))).isNotEqualTo(base);
            assertThat(service.makeDedupeKey("Principal", date, "Mercadona", eur(-45.31))).isNotEqualTo(base);
        }
    }

    // ---------- reading raw files ----------

    @Nested
    class ReadCsv {

        @Test
        void semicolonDelimited() {
            ParsedTable table = service.readAny("mov.csv", utf8(
                    "Fecha;Concepto;Importe\n15/03/2026;Mercadona;-45,30\n16/03/2026;Nomina;1500,00\n"));

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe");
            assertThat(table.rows()).hasSize(2);
            assertThat(table.rows().get(0)).containsEntry("Concepto", "Mercadona").containsEntry("Importe", "-45,30");
        }

        @Test
        void commaDelimited() {
            ParsedTable table = service.readAny("mov.csv", utf8(
                    "Fecha,Concepto,Importe\n15/03/2026,Mercadona,-45.30\n"));

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe");
            assertThat(table.rows()).hasSize(1);
        }

        @Test
        void tabDelimited() {
            ParsedTable table = service.readAny("mov.csv", utf8(
                    "Fecha\tConcepto\tImporte\n15/03/2026\tMercadona\t-45.30\n"));

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe");
        }

        // readCsvRaw takes the first delimiter (; , tab) that yields more than one
        // column. A tab file whose amounts use decimal commas ("-45,30") therefore
        // gets split on the comma inside the data rows instead of on the tabs.
        @Test
        void tabDelimitedWithDecimalCommas() {
            ParsedTable table = service.readAny("mov.csv", utf8(
                    "Fecha\tConcepto\tImporte\n15/03/2026\tMercadona\t-45,30\n"));

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe");
        }

        @Test
        void stripsUtf8Bom() {
            byte[] body = utf8("Fecha;Concepto;Importe\n15/03/2026;Mercadona;-45,30\n");
            byte[] withBom = new byte[body.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);

            ParsedTable table = service.readAny("mov.csv", withBom);

            assertThat(table.columns().get(0)).isEqualTo("Fecha"); // not "﻿Fecha"
        }

        @Test
        void keepsAccentsInUtf8() {
            ParsedTable table = service.readAny("mov.csv", utf8(
                    "Fecha;Concepto;Importe\n15/03/2026;Nómina empresa;1500,00\n"));

            assertThat(table.rows().get(0)).containsEntry("Concepto", "Nómina empresa");
        }

        // Many Spanish bank exports are ISO-8859-1. Decoding those bytes as UTF-8
        // doesn't throw (invalid bytes become U+FFFD), so the ISO-8859-1 fallback
        // in readCsvRaw never gets a chance and "Nómina" arrives as "N?mina".
        @Test
        void keepsAccentsInLatin1() {
            byte[] latin1 = bytes(
                    "Fecha;Concepto;Importe\n15/03/2026;Nómina empresa;1500,00\n", StandardCharsets.ISO_8859_1);

            ParsedTable table = service.readAny("mov.csv", latin1);

            assertThat(table.rows().get(0)).containsEntry("Concepto", "Nómina empresa");
        }

        @Test
        void singleColumnFileIsRejectedWithClearMessage() {
            assertThatThrownBy(() -> service.readAny("mov.csv", utf8("solo una columna\notra linea\n")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No se ha podido leer el archivo");
        }

        @Test
        void emptyFileIsRejectedWithClearMessage() {
            assertThatThrownBy(() -> service.readAny("mov.csv", new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ReadExcel {

        private byte[] workbook() throws Exception {
            try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet sheet = wb.createSheet("Movimientos");
                CellStyle dateStyle = wb.createCellStyle();
                dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));

                sheet.createRow(0).createCell(0).setCellValue("Movimientos de la cuenta");
                sheet.createRow(1).createCell(0).setCellValue("Titular: Juan");
                // row 2 left absent on purpose (blank row in the export)
                Row header = sheet.createRow(3);
                header.createCell(0).setCellValue("Fecha");
                header.createCell(1).setCellValue("Concepto");
                header.createCell(2).setCellValue("Importe");
                header.createCell(3).setCellValue("Saldo");

                Row r1 = sheet.createRow(4);
                Cell date = r1.createCell(0);
                date.setCellValue(LocalDate.of(2026, 3, 15));
                date.setCellStyle(dateStyle);
                r1.createCell(1).setCellValue("Mercadona");
                r1.createCell(2).setCellValue(-45.3);
                r1.createCell(3).setCellValue(1000.0);

                wb.write(out);
                return out.toByteArray();
            }
        }

        @Test
        void skipsTitleRowsAndReadsTypedCells() throws Exception {
            ParsedTable table = service.readAny("mov.xlsx", workbook());

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe", "Saldo");
            assertThat(table.rows()).hasSize(1);
            Map<String, String> first = table.rows().get(0);
            assertThat(service.normalizeDate(first.get("Fecha"))).isEqualTo(LocalDate.of(2026, 3, 15));
            assertThat(first.get("Concepto")).isEqualTo("Mercadona");
            assertThat(service.normalizeAmount(first.get("Importe"))).isEqualByComparingTo(BigDecimal.valueOf(-45.3));
        }

        // ---- date cells: read the real date, not the text POI would render for the cell's format ----

        private byte[] statement(LocalDate date, short builtinFormat, String customFormat, boolean asFormula) throws Exception {
            try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet sheet = wb.createSheet("Movimientos");
                Row header = sheet.createRow(0);
                header.createCell(0).setCellValue("Fecha");
                header.createCell(1).setCellValue("Concepto");
                header.createCell(2).setCellValue("Importe");
                Row r = sheet.createRow(1);
                Cell cell = r.createCell(0);
                if (asFormula) {
                    cell.setCellFormula("DATE(%d,%d,%d)".formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
                } else {
                    cell.setCellValue(date);
                }
                CellStyle style = wb.createCellStyle();
                style.setDataFormat(customFormat != null
                        ? wb.getCreationHelper().createDataFormat().getFormat(customFormat)
                        : builtinFormat);
                cell.setCellStyle(style);
                r.createCell(1).setCellValue("Mercadona");
                r.createCell(2).setCellValue(-45.3);
                wb.getCreationHelper().createFormulaEvaluator().evaluateAll(); // store cached formula results
                wb.write(out);
                return out.toByteArray();
            }
        }

        private String dateCellText(byte[] xlsx) {
            return service.readAny("mov.xlsx", xlsx).rows().get(0).get("Fecha");
        }

        // Excel's default short date (built-in 14) reaches us as "3/15/26" (US order) whatever the locale,
        // which no supported format parsed: every row of such a file was rejected.
        @ParameterizedTest(name = "built-in format {0}")
        @ValueSource(shorts = {14, 15, 16, 17, 22})
        void builtInDateFormatsAreReadAsTheRealDate(short format) throws Exception {
            String text = dateCellText(statement(LocalDate.of(2026, 3, 15), format, null, false));

            assertThat(service.normalizeDate(text)).isEqualTo(LocalDate.of(2026, 3, 15));
        }

        @ParameterizedTest(name = "custom format {0}")
        @ValueSource(strings = {"dd/mm/yyyy", "yyyy-mm-dd", "dd-mmm-yyyy", "mmmm d, yyyy", "d/m/yy", "dd.mm.yyyy hh:mm"})
        void customDateFormatsAreReadAsTheRealDate(String format) throws Exception {
            String text = dateCellText(statement(LocalDate.of(2026, 3, 15), (short) 0, format, false));

            assertThat(service.normalizeDate(text)).isEqualTo(LocalDate.of(2026, 3, 15));
        }

        // If we naively accepted POI's US-order text ("4/3/26"), April 3rd would be read as 4 March.
        @Test
        void dayAndMonthAreNeverSwapped() throws Exception {
            String text = dateCellText(statement(LocalDate.of(2026, 4, 3), (short) 14, null, false));

            assertThat(service.normalizeDate(text)).isEqualTo(LocalDate.of(2026, 4, 3));
        }

        @Test
        void resultDoesNotDependOnTheJvmLocale() throws Exception {
            byte[] xlsx = statement(LocalDate.of(2026, 3, 15), (short) 14, null, false);
            Locale original = Locale.getDefault(Locale.Category.FORMAT);
            try {
                for (String tag : new String[]{"es-ES", "en-US", "en-GB", "de-DE"}) {
                    Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(tag));
                    assertThat(service.normalizeDate(dateCellText(xlsx))).as(tag).isEqualTo(LocalDate.of(2026, 3, 15));
                }
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, original);
            }
        }

        @Test
        void formulaCellsThatEvaluateToADateAreRead() throws Exception {
            String text = dateCellText(statement(LocalDate.of(2026, 3, 15), (short) 14, null, true));

            assertThat(service.normalizeDate(text)).isEqualTo(LocalDate.of(2026, 3, 15));
        }

        @Test
        void aTimeOnlyCellStaysAsItsTimeText() throws Exception {
            try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet sheet = wb.createSheet();
                sheet.createRow(0).createCell(0).setCellValue("Hora");
                sheet.getRow(0).createCell(1).setCellValue("Concepto");
                Cell time = sheet.createRow(1).createCell(0);
                time.setCellValue(10.5 / 24);                         // 10:30, no date part
                CellStyle style = wb.createCellStyle();
                style.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("hh:mm"));
                time.setCellStyle(style);
                sheet.getRow(1).createCell(1).setCellValue("x");
                wb.write(out);

                assertThat(service.readAny("h.xlsx", out.toByteArray()).rows().get(0).get("Hora")).isEqualTo("10:30");
            }
        }

        // A numeric cell WITHOUT a date format is just a number: there is no safe way to tell 46096 from an amount.
        @Test
        void aNumberWithoutDateFormatIsNotTurnedIntoADate() throws Exception {
            String text = dateCellText(statement(LocalDate.of(2026, 3, 15), (short) 0, null, false));

            assertThat(text).isEqualTo("46096");
            assertThat(service.normalizeDate(text)).isNull();
        }

        @Test
        void amountsAndDescriptionsInExcelAreUnaffected() throws Exception {
            var row = service.readAny("mov.xlsx", statement(LocalDate.of(2026, 3, 15), (short) 14, null, false)).rows().get(0);

            assertThat(row).containsEntry("Concepto", "Mercadona");
            assertThat(service.normalizeAmount(row.get("Importe"))).isEqualByComparingTo(BigDecimal.valueOf(-45.3));
        }

        // ---- legacy .xls (HSSF): a different code path in POI from .xlsx ----

        private byte[] legacyXls(short dateFormat) throws Exception {
            try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet sheet = wb.createSheet("Movimientos");
                sheet.createRow(0).createCell(0).setCellValue("Movimientos de la cuenta");
                Row header = sheet.createRow(1);
                header.createCell(0).setCellValue("Fecha");
                header.createCell(1).setCellValue("Concepto");
                header.createCell(2).setCellValue("Importe");
                Row r = sheet.createRow(2);
                Cell date = r.createCell(0);
                date.setCellValue(LocalDate.of(2026, 4, 3));
                CellStyle style = wb.createCellStyle();
                style.setDataFormat(dateFormat);
                date.setCellStyle(style);
                r.createCell(1).setCellValue("Mercadona");
                r.createCell(2).setCellValue(-45.3);
                wb.write(out);
                return out.toByteArray();
            }
        }

        @Test
        void legacyXlsIsReadLikeXlsx() throws Exception {
            ParsedTable table = service.readAny("mov.xls", legacyXls((short) 14));

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe");
            assertThat(table.rows()).hasSize(1);
            Map<String, String> first = table.rows().get(0);
            assertThat(service.normalizeDate(first.get("Fecha"))).isEqualTo(LocalDate.of(2026, 4, 3)); // not 4 March
            assertThat(first.get("Concepto")).isEqualTo("Mercadona");
            assertThat(service.normalizeAmount(first.get("Importe"))).isEqualByComparingTo(BigDecimal.valueOf(-45.3));
        }

        @Test
        void fileExtensionIsMatchedCaseInsensitively() throws Exception {
            assertThat(service.readAny("MOVIMIENTOS.XLS", legacyXls((short) 14)).rows()).hasSize(1);
        }

        @Test
        void xlsContentUnderACsvNameIsNotMisreadAsExcel() throws Exception {
            // routing is by extension: binary .xls bytes named .csv go down the CSV path and are rejected cleanly
            assertThatThrownBy(() -> service.readAny("mov.csv", legacyXls((short) 14)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void corruptFileIsRejectedAsBadRequestNotServerError() {
            assertThatThrownBy(() -> service.readAny("mov.xlsx", utf8("esto no es un excel")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------- header detection & table cleanup ----------

    @Nested
    class TableExtraction {

        @Test
        void skipsTitleAndMetadataRowsAboveHeader() {
            ParsedTable table = service.readAny("mov.csv", utf8("""
                    Movimientos de la cuenta;;;
                    Titular: Juan;;;

                    Fecha;Concepto;Importe;Saldo
                    15/03/2026;Mercadona;-45,30;1000,00
                    16/03/2026;Nomina;1500,00;2500,00
                    """));

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe", "Saldo");
            assertThat(table.rows()).hasSize(2);
        }

        @Test
        void fallsBackToFirstRowWhenNoRowLooksLikeAHeader() {
            ParsedTable table = service.readAny("mov.csv", utf8("col1;col2\n1;2\n3;4\n"));

            assertThat(table.columns()).containsExactly("col1", "col2");
            assertThat(table.rows()).hasSize(2);
        }

        @Test
        void rowsShorterThanTheHeaderAreCompletedWithBlanksInsteadOfFailing() {
            ParsedTable table = service.readAny("mov.csv", utf8(
                    "Fecha;Concepto;Importe\n15/03/2026;Mercadona\n16/03/2026;Lidl;-9,00\n"));

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe");
            assertThat(table.rows()).hasSize(2);
            assertThat(table.rows().get(0)).containsEntry("Importe", "");

            ImportService.BuildResult result = service.buildRows(table, service.guessMapping(table.columns()), "Principal");
            assertThat(result.rows()).hasSize(1);   // the short row has no amount
            assertThat(result.errors()).isEqualTo(1);
        }

        @Test
        void renamesDuplicateHeaders() {
            ParsedTable table = service.readAny("mov.csv", utf8(
                    "Fecha;Fecha;Importe\n15/03/2026;16/03/2026;-45,30\n"));

            assertThat(table.columns()).containsExactly("Fecha", "Fecha (1)", "Importe");
        }

        @Test
        void dropsFullyBlankColumnsAndRows() {
            ParsedTable table = service.readAny("mov.csv", utf8(
                    "Fecha;Concepto;;Importe\n15/03/2026;Mercadona;;-45,30\n;;;\n16/03/2026;Lidl;;-10,00\n"));

            assertThat(table.columns()).containsExactly("Fecha", "Concepto", "Importe");
            assertThat(table.rows()).hasSize(2);
        }
    }

    // ---------- guessMapping ----------

    @Nested
    class GuessMapping {

        @Test
        void simpleStatement() {
            Map<String, String> m = service.guessMapping(List.of("Fecha", "Concepto", "Importe", "Saldo"));

            assertThat(m).containsEntry("date", "Fecha")
                    .containsEntry("description", "Concepto")
                    .containsEntry("amount", "Importe")
                    .containsEntry("balance", "Saldo");
            assertThat(m.get("debit")).isNull();
            assertThat(m.get("credit")).isNull();
        }

        @Test
        void bbvaStyleStatement() {
            Map<String, String> m = service.guessMapping(
                    List.of("Fecha", "Fecha valor", "Concepto", "Movimiento", "Importe", "Divisa", "Disponible"));

            assertThat(m).containsEntry("date", "Fecha")
                    .containsEntry("description", "Concepto")
                    .containsEntry("description_extra", "Movimiento")
                    .containsEntry("amount", "Importe")
                    .containsEntry("balance", "Disponible");
        }

        @Test
        void exactMatchBeatsSubstringMatch() {
            Map<String, String> m = service.guessMapping(List.of("Importe total", "Importe"));

            assertThat(m).containsEntry("amount", "Importe");
        }

        @Test
        void matchingIsCaseInsensitive() {
            Map<String, String> m = service.guessMapping(List.of("FECHA", "CONCEPTO", "IMPORTE"));

            assertThat(m).containsEntry("date", "FECHA")
                    .containsEntry("description", "CONCEPTO")
                    .containsEntry("amount", "IMPORTE");
        }

        @Test
        void descriptionExtraIsDroppedWhenItIsTheSameColumnAsDescription() {
            Map<String, String> m = service.guessMapping(List.of("Fecha", "Detalle referencia", "Importe"));

            assertThat(m).containsEntry("description", "Detalle referencia");
            assertThat(m.get("description_extra")).isNull();
        }

        // "valor" is a hint for the amount, but here it only appears inside the
        // "Fecha valor" date column. With no "Importe" column, amount gets mapped
        // to that date column and buildRows then never falls back to Cargo/Abono.
        @Test
        void debitCreditStatementDoesNotMapAmountToADateColumn() {
            Map<String, String> m = service.guessMapping(
                    List.of("Fecha", "Fecha valor", "Concepto", "Cargo", "Abono", "Saldo"));

            assertThat(m.get("amount")).isNull();
            assertThat(m).containsEntry("debit", "Cargo").containsEntry("credit", "Abono");
        }
    }

    // ---------- buildRows ----------

    @Nested
    class BuildRows {

        private final Map<String, String> amountMapping = mapping(
                "date", "Fecha", "description", "Concepto", "amount", "Importe");

        private Map<String, String> mapping(String... keyValues) {
            return row(keyValues);
        }

        @SafeVarargs
        private ParsedTable table(Map<String, String>... rows) {
            return new ParsedTable(List.of("Fecha", "Concepto", "Importe"), List.of(rows));
        }

        @SafeVarargs
        private ParsedTable tableOf(List<String> columns, Map<String, String>... rows) {
            return new ParsedTable(columns, List.of(rows));
        }

        @Test
        void buildsRowsFromSingleAmountColumn() {
            ImportService.BuildResult result = service.buildRows(table(
                    row("Fecha", "15/03/2026", "Concepto", "Mercadona", "Importe", "-45,30")), amountMapping, "Principal");

            assertThat(result.errors()).isZero();
            assertThat(result.rows()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("alternativeDescriptions")
                    .containsExactly(new ImportRow(LocalDate.of(2026, 3, 15), "Mercadona", eur(-45.30), "Principal", null));
        }

        // The stored description is the description column plus the optional detail column, and the dedupe key
        // hashes it. Each row also carries what it would have been called under the other choices, so a
        // statement imported before with another detail column is still recognised.
        private final List<String> detailedColumns = List.of("Fecha", "Concepto", "Movimiento", "Divisa", "Importe", "Saldo");

        private ImportRow detailedRow(Map<String, String> mapping) {
            return service.buildRows(tableOf(detailedColumns, row(
                    "Fecha", "15/03/2026", "Concepto", "COMPRA MERCADONA", "Movimiento", "Compra con tarjeta",
                    "Divisa", "EUR", "Importe", "-45,30", "Saldo", "954,70")), mapping, "Principal").rows().get(0);
        }

        @Test
        void aRowCarriesTheDescriptionsItWouldHaveHadWithOtherDetailColumns() {
            ImportRow built = detailedRow(mapping("date", "Fecha", "description", "Concepto", "amount", "Importe"));

            assertThat(built.description()).isEqualTo("COMPRA MERCADONA");
            assertThat(built.alternativeDescriptions()).containsExactly(
                    Map.entry("alone", "COMPRA MERCADONA"),
                    Map.entry("with:Movimiento", "COMPRA MERCADONA — Compra con tarjeta"),
                    Map.entry("with:Divisa", "COMPRA MERCADONA — EUR"),
                    // not mapped to anything here, so it is a candidate like any other (harmless: it only
                    // matters if an earlier import really used it as the detail)
                    Map.entry("with:Saldo", "COMPRA MERCADONA — 954,70"));
        }

        @Test
        void theChosenDetailColumnDoesNotChangeTheDescriptionStoredNorTheAlternativesOffered() {
            ImportRow built = detailedRow(mapping(
                    "date", "Fecha", "description", "Concepto", "description_extra", "Movimiento", "amount", "Importe"));

            assertThat(built.description()).isEqualTo("COMPRA MERCADONA — Compra con tarjeta");
            assertThat(built.alternativeDescriptions()).containsEntry("alone", "COMPRA MERCADONA")
                    .containsEntry("with:Divisa", "COMPRA MERCADONA — EUR");
        }

        @Test
        void dateAmountDebitCreditBalanceAndDescriptionColumnsAreNeverOfferedAsDetails() {
            ImportRow built = detailedRow(mapping(
                    "date", "Fecha", "description", "Concepto", "amount", "Importe", "balance", "Saldo"));

            assertThat(built.alternativeDescriptions().keySet())
                    .containsExactly("alone", "with:Movimiento", "with:Divisa")
                    .doesNotContain("with:Fecha", "with:Importe", "with:Saldo", "with:Concepto");
        }

        @Test
        void debitAndCreditColumnsAreNotOfferedAsDetailsEither() {
            List<String> cols = List.of("Fecha", "Concepto", "Cargo", "Abono", "Tipo");

            ImportRow built = service.buildRows(tableOf(cols, row(
                    "Fecha", "15/03/2026", "Concepto", "Compra", "Cargo", "45,30", "Abono", "", "Tipo", "Tarjeta")),
                    mapping("date", "Fecha", "description", "Concepto", "debit", "Cargo", "credit", "Abono"), "Principal")
                    .rows().get(0);

            assertThat(built.alternativeDescriptions().keySet()).containsExactly("alone", "with:Tipo");
        }

        @Test
        void anEmptyDetailCellGivesTheDescriptionAloneAndAnEmptyDescriptionFallsBackToTheDetail() {
            List<String> cols = List.of("Fecha", "Concepto", "Tipo", "Importe");

            List<ImportRow> rows = service.buildRows(tableOf(cols,
                    row("Fecha", "15/03/2026", "Concepto", "Compra", "Tipo", "", "Importe", "-1,00"),
                    row("Fecha", "15/03/2026", "Concepto", "", "Tipo", "Tarjeta", "Importe", "-1,00")),
                    mapping("date", "Fecha", "description", "Concepto", "amount", "Importe"), "Principal").rows();

            assertThat(rows.get(0).alternativeDescriptions()).containsEntry("with:Tipo", "Compra");
            assertThat(rows).hasSize(1).as("a row with no description at all is still an error, not built from the detail");
        }

        @Test
        void countsIncompleteRowsAsErrorsAndSkipsThem() {
            ImportService.BuildResult result = service.buildRows(table(
                    row("Fecha", "", "Concepto", "Sin fecha", "Importe", "-1,00"),
                    row("Fecha", "15/03/2026", "Concepto", "Sin importe", "Importe", ""),
                    row("Fecha", "15/03/2026", "Concepto", "", "Importe", "-1,00"),
                    row("Fecha", "15/03/2026", "Concepto", "Buena", "Importe", "-1,00")), amountMapping, "Principal");

            assertThat(result.rows()).hasSize(1);
            assertThat(result.errors()).isEqualTo(3);
        }

        @Test
        void debitAndCreditColumns() {
            Map<String, String> m = mapping("date", "Fecha", "description", "Concepto", "debit", "Cargo", "credit", "Abono");
            List<String> cols = List.of("Fecha", "Concepto", "Cargo", "Abono");

            ImportService.BuildResult result = service.buildRows(tableOf(cols,
                    row("Fecha", "15/03/2026", "Concepto", "Compra", "Cargo", "45,30", "Abono", ""),
                    row("Fecha", "16/03/2026", "Concepto", "Nomina", "Cargo", "", "Abono", "1500,00"),
                    row("Fecha", "17/03/2026", "Concepto", "Ya negativo", "Cargo", "-10,00", "Abono", ""),
                    row("Fecha", "18/03/2026", "Concepto", "Vacio", "Cargo", "", "Abono", "")), m, "Principal");

            assertThat(result.rows()).extracting(ImportRow::amount).containsExactly(eur(-45.30), eur(1500.0), eur(-10.0));
            assertThat(result.errors()).isEqualTo(1);
        }

        @Test
        void onlyADebitColumnMakesEveryAmountAnExpense() {
            Map<String, String> m = mapping("date", "Fecha", "description", "Concepto", "debit", "Cargo");
            List<String> cols = List.of("Fecha", "Concepto", "Cargo");

            ImportService.BuildResult result = service.buildRows(tableOf(cols,
                    row("Fecha", "15/03/2026", "Concepto", "Compra", "Cargo", "45,30"),
                    row("Fecha", "16/03/2026", "Concepto", "Ya negativo", "Cargo", "-10,00"),
                    row("Fecha", "17/03/2026", "Concepto", "Vacio", "Cargo", ""),
                    row("Fecha", "18/03/2026", "Concepto", "Cero", "Cargo", "0,00")), m, "Principal");

            assertThat(result.rows()).extracting(ImportRow::amount).containsExactly(eur(-45.30), eur(-10.0));
            assertThat(result.errors()).isEqualTo(2); // a blank or zero debit is not a transaction
        }

        @Test
        void onlyACreditColumnKeepsAmountsAsIncome() {
            Map<String, String> m = mapping("date", "Fecha", "description", "Concepto", "credit", "Abono");
            List<String> cols = List.of("Fecha", "Concepto", "Abono");

            ImportService.BuildResult result = service.buildRows(tableOf(cols,
                    row("Fecha", "15/03/2026", "Concepto", "Nomina", "Abono", "1.500,00"),
                    row("Fecha", "16/03/2026", "Concepto", "Vacio", "Abono", ""),
                    row("Fecha", "17/03/2026", "Concepto", "Cero", "Abono", "0,00")), m, "Principal");

            assertThat(result.rows()).extracting(ImportRow::amount).containsExactly(eur(1500.0));
            assertThat(result.errors()).isEqualTo(2);
        }

        @Test
        void creditTakesPrecedenceWhenBothColumnsAreFilled() {
            Map<String, String> m = mapping("date", "Fecha", "description", "Concepto", "debit", "Cargo", "credit", "Abono");
            List<String> cols = List.of("Fecha", "Concepto", "Cargo", "Abono");

            ImportService.BuildResult result = service.buildRows(tableOf(cols,
                    row("Fecha", "15/03/2026", "Concepto", "Raro", "Cargo", "50,00", "Abono", "100,00"),
                    row("Fecha", "16/03/2026", "Concepto", "Abono a cero", "Cargo", "50,00", "Abono", "0,00")), m, "Principal");

            assertThat(result.rows()).extracting(ImportRow::amount).containsExactly(eur(100.0), eur(-50.0));
        }

        @Test
        void joinsExtraDescriptionOnlyWhenItAddsInformation() {
            Map<String, String> m = mapping("date", "Fecha", "description", "Concepto",
                    "description_extra", "Movimiento", "amount", "Importe");
            List<String> cols = List.of("Fecha", "Concepto", "Movimiento", "Importe");

            ImportService.BuildResult result = service.buildRows(tableOf(cols,
                    row("Fecha", "15/03/2026", "Concepto", "Compra", "Movimiento", "Tarjeta", "Importe", "-1,00"),
                    row("Fecha", "15/03/2026", "Concepto", "Compra", "Movimiento", "COMPRA", "Importe", "-2,00"),
                    row("Fecha", "15/03/2026", "Concepto", "", "Movimiento", "Solo extra", "Importe", "-3,00")), m, "Principal");

            assertThat(result.rows()).extracting(ImportRow::description)
                    .containsExactly("Compra — Tarjeta", "Compra", "Solo extra");
        }

        @Test
        void readsBalanceWhenMapped() {
            Map<String, String> m = mapping("date", "Fecha", "description", "Concepto", "amount", "Importe", "balance", "Saldo");
            List<String> cols = List.of("Fecha", "Concepto", "Importe", "Saldo");

            ImportService.BuildResult result = service.buildRows(tableOf(cols,
                    row("Fecha", "15/03/2026", "Concepto", "Compra", "Importe", "-1,00", "Saldo", "1.000,50"),
                    row("Fecha", "16/03/2026", "Concepto", "Otra", "Importe", "-1,00", "Saldo", "")), m, "Principal");

            assertThat(result.rows()).extracting(ImportRow::balance).containsExactly(eur(1000.5), null);
        }

        @Test
        void unmappedRequiredColumnsMakeEveryRowAnError() {
            Map<String, String> m = mapping("description", "Concepto", "amount", "Importe"); // no date

            ImportService.BuildResult result = service.buildRows(table(
                    row("Fecha", "15/03/2026", "Concepto", "Compra", "Importe", "-1,00")), m, "Principal");

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).isEqualTo(1);
        }
    }

    // ---------- end to end: file -> mapping -> rows ----------

    @Test
    void csvToRowsEndToEnd() {
        ParsedTable table = service.readAny("mov.csv", utf8("""
                Movimientos de la cuenta;;;
                Fecha;Concepto;Importe;Saldo
                15/03/2026;Mercadona;-45,30;1.000,00
                16/03/2026;Nomina;1.500,00;2.500,00
                31/12/9999x;Fila mala;-1,00;0,00
                """));

        ImportService.BuildResult result = service.buildRows(table, service.guessMapping(table.columns()), "Principal");

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.rows()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("alternativeDescriptions")
                .containsExactly(
                        new ImportRow(LocalDate.of(2026, 3, 15), "Mercadona", eur(-45.30), "Principal", eur(1000.0)),
                        new ImportRow(LocalDate.of(2026, 3, 16), "Nomina", eur(1500.0), "Principal", eur(2500.0)));
    }
}
