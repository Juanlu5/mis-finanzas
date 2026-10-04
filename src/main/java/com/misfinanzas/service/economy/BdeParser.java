package com.misfinanzas.service.economy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the Bank of Spain's statistics API (app.bde.es/bierest, "listaSeries"): one object per series with parallel
 * {@code fechas} (ISO instants, newest first) and {@code valores}. Only monthly series are used here, so each date
 * becomes its month.
 */
public final class BdeParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private BdeParser() {
    }

    /** The first series' points, oldest first, as ("2026-08", 2.954). */
    public static List<IneParser.Point> monthly(String json) {
        JsonNode series;
        try {
            series = JSON.readTree(json).path(0);
        } catch (JacksonException e) {
            throw new EconomyFetchException("El Banco de España ha devuelto algo que no se entiende.");
        }
        JsonNode dates = series.path("fechas");
        JsonNode values = series.path("valores");
        if (!dates.isArray() || dates.isEmpty()) throw new EconomyFetchException("El Banco de España no ha devuelto datos.");
        List<IneParser.Point> points = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            JsonNode value = values.path(i);
            String date = dates.path(i).asString("");
            if (!value.isNumber() || date.length() < 7) continue;
            points.add(new IneParser.Point(date.substring(0, 7), value.doubleValue()));
        }
        points.sort(Comparator.comparing(IneParser.Point::period));
        return points;
    }
}
