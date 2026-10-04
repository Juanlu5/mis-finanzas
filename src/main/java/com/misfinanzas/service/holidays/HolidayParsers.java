package com.misfinanzas.service.holidays;

import com.misfinanzas.service.economy.EconomyFetchException;
import com.misfinanzas.service.economy.Region;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the three holiday sources: the BOE's daily summary (to find the resolution listing next year's holidays),
 * that resolution's table (every holiday, one column per region), and the Generalitat's local holidays of Catalan
 * towns (analisi.transparenciacatalunya.cat, dataset b4eh-r8up).
 */
public final class HolidayParsers {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final List<String> MONTHS = List.of("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
            "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre");
    private static final Pattern DAY_ROW = Pattern.compile("^(\\d{1,2})\\s+(.+?)\\.?$");
    // how the BOE heads its region columns, footnote numbers like "Canarias (1)" removed
    private static final Map<String, Region> BOE_COLUMNS = Map.ofEntries(
            Map.entry("Andalucía", Region.AN), Map.entry("Aragón", Region.AR), Map.entry("Asturias", Region.AS),
            Map.entry("Illes Balears", Region.IB), Map.entry("Canarias", Region.CN), Map.entry("Cantabria", Region.CB),
            Map.entry("Castilla-La Mancha", Region.CM), Map.entry("Castilla y León", Region.CL), Map.entry("Cataluña", Region.CT),
            Map.entry("Extremadura", Region.EX), Map.entry("Galicia", Region.GA), Map.entry("Com. Madrid", Region.MD),
            Map.entry("Región Murcia", Region.MC), Map.entry("C. Foral Navarra", Region.NC), Map.entry("País Vasco", Region.PV),
            Map.entry("La Rioja", Region.RI), Map.entry("Comunitat Valenciana", Region.VC), Map.entry("Ciudad de Ceuta", Region.CE),
            Map.entry("Ciudad de Melilla", Region.ML));

    private HolidayParsers() {
    }

    /** One holiday of the BOE's table: which regions have it, as "national" or "region". */
    public record BoeHoliday(LocalDate day, String name, Map<Region, String> kinds) {
    }

    public record Town(String code, String name) {
    }

    /** The id of the resolution "... relación de fiestas laborales para el año {year}" in a BOE daily summary, or null. */
    public static String findResolution(String summaryJson, int year) {
        JsonNode root;
        try {
            root = JSON.readTree(summaryJson);
        } catch (JacksonException e) {
            throw new EconomyFetchException("El BOE ha devuelto algo que no se entiende.");
        }
        return find(root, "relación de fiestas laborales para el año " + year);
    }

    private static String find(JsonNode node, String title) {
        if (node.isObject()) {
            if (node.path("titulo").asString("").contains(title) && node.has("identificador")) return node.path("identificador").asString();
            for (var entry : node.properties()) {
                String found = find(entry.getValue(), title);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = find(child, title);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The resolution's table: a header row naming the regions, then month rows ("Enero") and day rows ("6 Epifanía
     * del Señor.") with a mark per region — "*" and "**" national holidays (the second kind a region could have
     * swapped and didn't), "***" the region's own. Here a day is "national" when every region has it.
     */
    public static List<BoeHoliday> boeTable(String xml, int year) {
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        Element table = doc.selectFirst("table");
        if (table == null) throw new EconomyFetchException("La resolución del BOE no trae la tabla de festivos.");
        List<Region> columns = null;
        Integer month = null;
        List<BoeHoliday> holidays = new ArrayList<>();
        for (Element row : table.select("tr")) {
            List<String> cells = row.select("td, th").stream().map(c -> c.text().replace(' ', ' ').strip()).toList();
            if (cells.isEmpty()) continue;
            if (columns == null) {
                if (cells.contains("Extremadura")) {
                    columns = cells.stream().map(c -> BOE_COLUMNS.get(c.replaceAll("\\s*\\(\\d+\\)$", ""))).toList();
                }
                continue;
            }
            if (MONTHS.contains(cells.get(0))) {
                month = MONTHS.indexOf(cells.get(0)) + 1;
                continue;
            }
            Matcher m = DAY_ROW.matcher(cells.get(0));
            if (month == null || !m.matches()) continue;
            LocalDate day;
            try {
                day = LocalDate.of(year, month, Integer.parseInt(m.group(1)));
            } catch (java.time.DateTimeException e) {
                continue;
            }
            List<Region> marked = new ArrayList<>();
            // the header has no cell over the dates column, so the marks line up with it from the second cell on
            for (int i = 1; i < cells.size() && i - 1 < columns.size(); i++) {
                Region region = columns.get(i - 1);
                if (region != null && !cells.get(i).isEmpty()) marked.add(region);
            }
            // "national" only when every region has it: a national holiday a region kept while others swapped it
            // (Jueves Santo, a Monday after a Sunday holiday) is, for the user, that region's
            long regions = columns.stream().filter(java.util.Objects::nonNull).count();
            String kind = marked.size() == regions ? "national" : "region";
            Map<Region, String> kinds = new EnumMap<>(Region.class);
            marked.forEach(r -> kinds.put(r, kind));
            if (!kinds.isEmpty()) holidays.add(new BoeHoliday(day, m.group(2).strip(), kinds));
        }
        if (columns == null || holidays.isEmpty()) throw new EconomyFetchException("No se ha podido leer la tabla de festivos del BOE.");
        return holidays;
    }

    /** The Generalitat's local holidays of one town: just the dates, the dataset names none. */
    public static List<LocalDate> localDays(String json) {
        List<LocalDate> days = new ArrayList<>();
        for (JsonNode row : rows(json)) {
            String date = row.path("data").asString("");
            try {
                if (date.length() >= 10) days.add(LocalDate.parse(date.substring(0, 10)));
            } catch (DateTimeParseException e) {
                // a row without a readable date is skipped
            }
        }
        return days;
    }

    /** Catalan towns with local holidays, as the Generalitat names them. */
    public static List<Town> towns(String json) {
        List<Town> towns = new ArrayList<>();
        for (JsonNode row : rows(json)) {
            String code = row.path("codi_municipi_ine").asString("");
            String name = row.path("ajuntament_o_nucli_municipal").asString("");
            if (!code.isEmpty() && !name.isEmpty()) towns.add(new Town(code, name));
        }
        return towns;
    }

    private static JsonNode rows(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray()) throw new EconomyFetchException("La Generalitat no ha devuelto datos.");
            return root;
        } catch (JacksonException e) {
            throw new EconomyFetchException("La Generalitat ha devuelto algo que no se entiende.");
        }
    }
}
