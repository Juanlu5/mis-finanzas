package com.misfinanzas.service.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the ECB's data API (data-api.ecb.europa.eu) in its CSV form ({@code format=csvdata}): a header row, then
 * one row per observation with {@code KEY}, {@code TIME_PERIOD} (a day, "2026-09-16") and {@code OBS_VALUE}. The
 * values are plain numbers and the titles, which may contain commas, come after the columns used here.
 */
public final class EcbParser {

    private EcbParser() {
    }

    /** Points per series key ("EXR.D.USD.EUR.SP00.A"), oldest first. */
    public static Map<String, List<IneParser.Point>> parse(String csv) {
        String[] lines = csv.strip().split("\\R");
        if (lines.length < 2) throw new EconomyFetchException("El BCE no ha devuelto datos.");
        List<String> header = List.of(lines[0].split(","));
        int key = header.indexOf("KEY"), time = header.indexOf("TIME_PERIOD"), value = header.indexOf("OBS_VALUE");
        if (key < 0 || time < 0 || value < 0) throw new EconomyFetchException("El BCE ha devuelto algo que no se entiende.");
        Map<String, List<IneParser.Point>> series = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            if (cells.length <= Math.max(key, Math.max(time, value))) continue;
            try {
                double v = Double.parseDouble(cells[value]);
                series.computeIfAbsent(cells[key], k -> new ArrayList<>()).add(new IneParser.Point(cells[time], v));
            } catch (NumberFormatException e) {
                // a missing observation is an empty cell
            }
        }
        series.values().forEach(list -> list.sort(Comparator.comparing(IneParser.Point::period)));
        return series;
    }
}
