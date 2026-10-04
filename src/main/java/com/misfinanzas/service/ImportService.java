package com.misfinanzas.service;

import com.misfinanzas.dto.ImportRow;
import com.misfinanzas.dto.ParsedTable;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;

/**
 * Turns an uploaded bank statement file into rows ready for insertion. Ported
 * from the original Python importer.py: same column-guessing heuristics and
 * locale-tolerant amount/date parsing, since real bank exports (BBVA-style)
 * are messy (title rows above the header, ES/EN number formats mixed).
 */
@Service
public class ImportService {

    // Ordered by priority: earlier hints win when several columns match.
    // An exact match always beats a substring match, regardless of order.
    private static final Map<String, List<String>> HINT_GROUPS = new LinkedHashMap<>();
    static {
        HINT_GROUPS.put("date", List.of("fecha", "date", "fecha operacion", "fecha operación", "fecha contable", "fecha valor"));
        HINT_GROUPS.put("description", List.of("concepto", "descripcion", "descripción", "detalle", "description"));
        HINT_GROUPS.put("description_extra", List.of("movimiento", "beneficiario", "ordenante", "referencia"));
        HINT_GROUPS.put("amount", List.of("importe", "amount", "cantidad", "valor"));
        HINT_GROUPS.put("debit", List.of("cargo", "debe", "debit", "salida"));
        HINT_GROUPS.put("credit", List.of("abono", "haber", "credit", "entrada"));
        HINT_GROUPS.put("balance", List.of("disponible", "saldo", "balance"));
    }

    // Words used to locate the real header row among title/metadata rows.
    private static final List<String> ALL_HEADER_WORDS = HINT_GROUPS.values().stream()
            .flatMap(List::stream)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    // STRICT (with "u" = plain year, not year-of-era): the default SMART resolver silently clamps a day
    // that doesn't exist in the month (31/02/2026 -> 28/02/2026), importing the row on the wrong date.
    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    private static final DateTimeFormatter[] DATE_FORMATS = {
            strict("dd/MM/uuuu"),
            strict("dd-MM-uuuu"),
            strict("uuuu-MM-dd"),
            strict("dd/MM/uu"),
            strict("MM/dd/uuuu"),
            strict("dd.MM.uuuu"),
    };

    // ---------- Reading raw files ----------

    public ParsedTable readAny(String filename, byte[] content) {
        String name = filename.toLowerCase();
        List<List<String>> raw = (name.endsWith(".xlsx") || name.endsWith(".xls"))
                ? readExcelRaw(content)
                : readCsvRaw(content);
        return extractTable(raw);
    }

    private List<List<String>> readExcelRaw(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<List<String>> rows = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    rows.add(List.of());
                    continue;
                }
                List<String> cells = new ArrayList<>();
                int lastCell = row.getLastCellNum();
                for (int c = 0; c < lastCell; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    cells.add(cellText(cell, formatter));
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalArgumentException("No se ha podido leer el archivo Excel: " + e.getMessage(), e);
        }
    }

    // Superset of ISO-8859-1 that also maps 0x80-0x9F (e.g. "€"), which is what
    // Spanish bank exports saved by Excel/Windows actually use.
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Date cells are read as the real date and rendered ISO ("2026-03-15"). Going through
     * DataFormatter instead yields text that depends on the cell's number format: Excel's default short
     * date arrives as "3/15/26" (US order, whatever the locale), "15-mar-26", etc., none of which
     * normalizeDate parses, and accepting the US order would silently swap day and month.
     * A numeric cell without a date format is left alone: 46096 can't be told apart from an amount.
     */
    private String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        // value >= 1 excludes time-only cells (10:30 is 0.4375), which keep their formatted text
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell) && cell.getNumericCellValue() >= 1) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatCellValue(cell).trim();
    }

    private List<List<String>> readCsvRaw(byte[] content) {
        String text = decodeText(content);
        char[] delimiters = {';', ',', '\t'};
        List<List<String>> best = null;
        int bestScore = 0;
        Exception lastError = null;
        for (char delimiter : delimiters) {
            try {
                CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).build();
                List<List<String>> rows = new ArrayList<>();
                try (CSVParser parser = CSVParser.parse(text, format)) {
                    for (CSVRecord record : parser) {
                        List<String> cells = new ArrayList<>();
                        record.forEach(cells::add);
                        rows.add(cells);
                    }
                }
                // Strict ">" keeps the earlier delimiter (; , tab) on ties.
                int score = consistentRowCount(rows);
                if (score > bestScore) {
                    best = rows;
                    bestScore = score;
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(
                    "No se ha podido leer el archivo. Prueba a exportarlo como CSV o Excel estándar. ("
                            + (lastError == null ? "formato no reconocido" : lastError.getMessage()) + ")");
        }
        return best;
    }

    /**
     * How well a delimiter fits: the number of rows sharing the most common
     * width, or 0 when that width is a single column (delimiter not present).
     * Picking by this instead of "first delimiter giving more than one column"
     * avoids splitting a tab-separated file on the decimal commas in its amounts.
     */
    private int consistentRowCount(List<List<String>> rows) {
        Map<Integer, Integer> countByWidth = new HashMap<>();
        for (List<String> row : rows) countByWidth.merge(row.size(), 1, Integer::sum);
        int bestWidth = 0;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> e : countByWidth.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() > bestWidth)) {
                bestWidth = e.getKey();
                bestCount = e.getValue();
            }
        }
        return bestWidth > 1 ? bestCount : 0;
    }

    /** UTF-8 if the bytes are valid UTF-8, otherwise Windows-1252. Drops a UTF-8 BOM. */
    private String decodeText(byte[] content) {
        int offset = hasUtf8Bom(content) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, offset, content.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, offset, content.length - offset, WINDOWS_1252);
        }
    }

    private boolean hasUtf8Bom(byte[] content) {
        return content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF;
    }

    // ---------- Header detection & table extraction ----------

    private int findHeaderRow(List<List<String>> raw) {
        int bestIdx = 0;
        int bestScore = -1;
        int maxScan = Math.min(25, raw.size());
        for (int i = 0; i < maxScan; i++) {
            List<String> values = raw.get(i).stream()
                    .filter(v -> !isBlank(v))
                    .map(v -> v.trim().toLowerCase())
                    .toList();
            if (values.isEmpty()) continue;
            int score = 0;
            for (String v : values) {
                if (ALL_HEADER_WORDS.stream().anyMatch(v::contains)) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }
        return bestScore >= 2 ? bestIdx : 0;
    }

    private List<String> dedupeColumnNames(List<String> headerValues) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < headerValues.size(); i++) {
            String raw = headerValues.get(i);
            String name = isBlank(raw) ? "Columna " + (i + 1) : raw.trim();
            if (seen.containsKey(name)) {
                int n = seen.get(name) + 1;
                seen.put(name, n);
                names.add(name + " (" + n + ")");
            } else {
                seen.put(name, 0);
                names.add(name);
            }
        }
        return names;
    }

    private ParsedTable extractTable(List<List<String>> raw) {
        int headerIdx = findHeaderRow(raw);
        List<String> header = dedupeColumnNames(raw.get(headerIdx));
        List<List<String>> dataRows = raw.subList(Math.min(headerIdx + 1, raw.size()), raw.size());

        int colCount = header.size();
        boolean[] allBlank = new boolean[colCount];
        Arrays.fill(allBlank, true);
        for (List<String> row : dataRows) {
            for (int c = 0; c < colCount; c++) {
                String v = c < row.size() ? row.get(c) : "";
                if (!isBlank(v)) allBlank[c] = false;
            }
        }

        List<String> keptColumns = new ArrayList<>();
        List<Integer> keptIndices = new ArrayList<>();
        for (int c = 0; c < colCount; c++) {
            if (!allBlank[c]) {
                keptColumns.add(header.get(c));
                keptIndices.add(c);
            }
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> row : dataRows) {
            Map<String, String> record = new LinkedHashMap<>();
            boolean rowAllBlank = true;
            for (int i = 0; i < keptIndices.size(); i++) {
                int c = keptIndices.get(i);
                String v = c < row.size() ? row.get(c) : "";
                if (!isBlank(v)) rowAllBlank = false;
                record.put(keptColumns.get(i), v);
            }
            if (!rowAllBlank) rows.add(record);
        }
        return new ParsedTable(keptColumns, rows);
    }

    // ---------- Column mapping ----------

    public Map<String, String> guessMapping(List<String> columns) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : HINT_GROUPS.entrySet()) {
            String bestCol = null;
            Double bestScore = null;
            for (String col : columns) {
                String low = col.trim().toLowerCase();
                // "Fecha valor" contains the amount hint "valor"; never let a date column win another field.
                if (!entry.getKey().equals("date") && score(low, HINT_GROUPS.get("date")) != null) continue;
                Double score = score(low, entry.getValue());
                if (score != null && (bestScore == null || score < bestScore)) {
                    bestScore = score;
                    bestCol = col;
                }
            }
            mapping.put(entry.getKey(), bestCol);
        }
        if (mapping.get("description_extra") != null
                && mapping.get("description_extra").equals(mapping.get("description"))) {
            mapping.put("description_extra", null);
        }
        return mapping;
    }

    private Double score(String colLow, List<String> hints) {
        for (int rank = 0; rank < hints.size(); rank++) {
            if (colLow.equals(hints.get(rank))) return rank - 1000.0; // exact match always wins
        }
        for (int rank = 0; rank < hints.size(); rank++) {
            if (colLow.contains(hints.get(rank))) return (double) rank;
        }
        return null;
    }

    // ---------- Value normalization ----------

    public BigDecimal normalizeAmount(String value) {
        if (isBlank(value)) return null;
        // Includes U+00A0/U+202F (non-breaking spaces), which Excel puts before "€" and which \s does not match.
        String s = value.replaceAll("[\\s\u00A0\u202F€$]", "");
        if (s.startsWith("+")) s = s.substring(1);
        if (s.contains(",") && s.contains(".")) {
            s = s.lastIndexOf(",") > s.lastIndexOf(".")
                    ? s.replace(".", "").replace(",", ".")
                    : s.replace(",", "");
        } else if (s.contains(",")) {
            // A trailing-zero cent (e.g. 2600,10) is sometimes exported with the
            // zero dropped (2600,1) — treat 1-2 digits after the comma as decimals,
            // not just exactly 2, or that gets misread as a thousands separator
            // and multiplies the value by ~10 (2600,1 -> wrongly "26001").
            String[] parts = s.split(",", -1);
            s = (parts.length == 2 && parts[1].length() <= 2) ? s.replace(",", ".") : s.replace(",", "");
        } else if (s.contains(".")) {
            // Dot only: "1.234" / "1.234.567" are Spanish thousands ("12.5" and "12.50" stay decimals),
            // except a leading zero ("0.125"), which is a decimal fraction.
            String[] parts = s.split("\\.", -1);
            boolean thousands = parts.length > 2 || (parts[1].length() == 3 && !parts[0].matches("-?0"));
            if (thousands) s = s.replace(".", "");
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public LocalDate normalizeDate(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("nat")) return null;
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }

    public String makeDedupeKey(String account, LocalDate date, String description, BigDecimal amount) {
        return makeDedupeKey(account, date, description, amount, 0);
    }

    /**
     * {@code occurrence} is the 0-based position of this row among identical rows (same account, date,
     * description and amount) in the same file, so two real, identical purchases on one day (two coffees
     * at the same cafe) don't collapse into one. Occurrence 0 hashes exactly like the original key, so
     * everything already stored keeps matching.
     *
     * <p>The amount is written into the key exactly as it was when amounts were doubles ("-45.3", "1700.0",
     * even "1.2E7"), rounding included: every stored key was hashed that way, and a key written any other way
     * ("-45.30") would no longer match and the next import or bank download would store everything again.
     */
    public String makeDedupeKey(String account, LocalDate date, String description, BigDecimal amount, int occurrence) {
        String raw = account + "|" + date + "|" + description.strip().toLowerCase() + "|"
                + Math.round(amount.doubleValue() * 100.0) / 100.0;
        if (occurrence > 0) raw += "|#" + occurrence;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- Row building ----------

    public record BuildResult(List<ImportRow> rows, int errors) {
    }

    public BuildResult buildRows(ParsedTable table, Map<String, String> mapping, String account) {
        List<ImportRow> rows = new ArrayList<>();
        int errors = 0;
        for (Map<String, String> record : table.rows()) {
            LocalDate date = mapping.get("date") != null ? normalizeDate(record.get(mapping.get("date"))) : null;

            String primary = cell(record, mapping.get("description"));
            String desc = joinDescription(primary, cell(record, mapping.get("description_extra")));

            BigDecimal amount = null;
            if (mapping.get("amount") != null) {
                amount = normalizeAmount(record.get(mapping.get("amount")));
            } else if (mapping.get("debit") != null || mapping.get("credit") != null) {
                BigDecimal debit = mapping.get("debit") != null ? normalizeAmount(record.get(mapping.get("debit"))) : null;
                BigDecimal credit = mapping.get("credit") != null ? normalizeAmount(record.get(mapping.get("credit"))) : null;
                if (credit != null && credit.signum() != 0) {
                    amount = credit;
                } else if (debit != null && debit.signum() != 0) {
                    amount = debit.abs().negate();
                }
            }

            if (date == null || amount == null || desc.isEmpty()) {
                errors++;
                continue;
            }

            BigDecimal balance = mapping.get("balance") != null ? normalizeAmount(record.get(mapping.get("balance"))) : null;
            rows.add(new ImportRow(date, desc, amount, account, balance,
                    alternativeDescriptions(record, mapping, table.columns(), primary)));
        }
        return new BuildResult(rows, errors);
    }

    /** Key of the description built from the description column alone. */
    public static final String DESCRIPTION_ALONE = "alone";

    private static String joinDescription(String description, String detail) {
        if (!detail.isEmpty() && !detail.equalsIgnoreCase(description)) {
            return description.isEmpty() ? detail : description + " — " + detail;
        }
        return description;
    }

    /**
     * The dedupe key hashes the stored description, which is the description column plus, when the user
     * picked one, a "detail" column. So the same statement imported with another choice of detail column
     * (or after the column guessing changed) would build other descriptions and be re-added in full.
     * To recognise it anyway, each row also carries the descriptions it would have had with the description
     * column alone, and with each other text column as the detail. The stored keys themselves are never
     * touched: this only widens what counts as "already there".
     * Columns holding the date, amounts or balance are never a detail, and the description column is the base.
     */
    private Map<String, String> alternativeDescriptions(Map<String, String> record, Map<String, String> mapping,
                                                        List<String> columns, String primary) {
        Set<String> notDetails = new HashSet<>();
        for (String field : List.of("date", "amount", "debit", "credit", "balance", "description")) {
            if (mapping.get(field) != null) notDetails.add(mapping.get(field));
        }
        Map<String, String> alternatives = new LinkedHashMap<>();
        alternatives.put(DESCRIPTION_ALONE, primary);
        for (String column : columns) {
            if (!notDetails.contains(column)) alternatives.put("with:" + column, joinDescription(primary, cell(record, column)));
        }
        return alternatives;
    }

    private String cell(Map<String, String> record, String column) {
        if (column == null) return "";
        String value = record.get(column);
        return isBlank(value) ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty() || value.trim().equalsIgnoreCase("nan");
    }
}
