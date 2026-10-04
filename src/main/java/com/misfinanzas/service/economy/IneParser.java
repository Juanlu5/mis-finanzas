package com.misfinanzas.service.economy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the INE's JSON API (servicios.ine.es/wstempus): a table ({@code DATOS_TABLA}) is a list of series, one
 * series ({@code DATOS_SERIE}) a single object. A series is named by its parts separated by ". " — "Cataluña.
 * Alimentos y bebidas no alcohólicas. Variación anual. " — which is how the right ones are picked: the codes
 * change whenever the INE rebases an index (it did in January 2026), the names don't.
 */
public final class IneParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private IneParser() {
    }

    /** {@code parts}: the name split, e.g. ["Cataluña", "Alimentos y bebidas no alcohólicas", "Variación anual"]. */
    public record Series(String code, List<String> parts, List<Point> points) {

        public String part(int i) {
            return i < parts.size() ? parts.get(i) : "";
        }

        public String last() {
            return parts.isEmpty() ? "" : parts.get(parts.size() - 1);
        }

        public boolean has(String part) {
            return parts.contains(part);
        }

        /** The region named anywhere in the name: not every table puts it first ("Viviendas. Cataluña. Número..."). */
        public java.util.Optional<Region> region() {
            return parts.stream().map(Region::fromIneName).flatMap(java.util.Optional::stream).findFirst();
        }
    }

    /** Oldest first. */
    public record Point(String period, double value) {
    }

    public static List<Series> parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new EconomyFetchException("El INE ha devuelto algo que no se entiende.");
        }
        List<Series> series = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode one : root) series.add(series(one));
        } else if (root.isObject() && root.has("Data")) {
            series.add(series(root));
        } else {
            throw new EconomyFetchException("El INE no ha devuelto datos.");
        }
        return series;
    }

    private static Series series(JsonNode node) {
        List<String> parts = Arrays.stream(node.path("Nombre").asString().split("\\.\\s"))
                .map(s -> s.strip().replaceAll("\\.$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
        List<Point> points = new ArrayList<>();
        for (JsonNode p : node.path("Data")) {
            JsonNode value = p.get("Valor");
            String period = period(p.path("Anyo").asInt(), p.path("FK_Periodo").asInt());
            if (value == null || !value.isNumber() || period == null) continue;
            points.add(new Point(period, value.doubleValue()));
        }
        points.sort(Comparator.comparing(Point::period));
        return new Series(node.path("COD").asString(), parts, points);
    }

    /** The INE's period ids: 1-12 are months, 19-22 quarters. Anything else (years, halves) isn't used here. */
    static String period(int year, int id) {
        if (year <= 0) return null;
        if (id >= 1 && id <= 12) return "%d-%02d".formatted(year, id);
        if (id >= 19 && id <= 22) return "%d-Q%d".formatted(year, id - 18);
        return null;
    }
}
