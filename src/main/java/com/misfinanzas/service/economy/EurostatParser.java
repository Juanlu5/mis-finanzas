package com.misfinanzas.service.economy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Eurostat's dissemination API (JSON-stat 2.0): {@code value} is a sparse map from a flat index to a number,
 * the flat index being the position in the cube of all dimensions ({@code id} and {@code size}, last one fastest).
 * The queries here fix every dimension but {@code geo} and {@code time}, which is what this pulls apart.
 */
public final class EurostatParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private EurostatParser() {
    }

    /** Points per geo code ("ES", "EU27_2020"), oldest first; periods as Eurostat gives them ("2026-08"). */
    public static Map<String, List<IneParser.Point>> byGeo(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new EconomyFetchException("Eurostat ha devuelto algo que no se entiende.");
        }
        if (root.has("error")) throw new EconomyFetchException("Eurostat no ha dado los datos: " + root.path("error").path(0).path("label").asString("sin motivo"));
        List<String> ids = new ArrayList<>();
        root.path("id").forEach(n -> ids.add(n.asString()));
        List<Integer> sizes = new ArrayList<>();
        root.path("size").forEach(n -> sizes.add(n.asInt()));
        int geoDim = ids.indexOf("geo"), timeDim = ids.indexOf("time");
        if (geoDim < 0 || timeDim < 0 || sizes.size() != ids.size()) throw new EconomyFetchException("Eurostat no ha devuelto datos.");
        String[] geos = labels(root.path("dimension").path("geo").path("category").path("index"), sizes.get(geoDim));
        String[] times = labels(root.path("dimension").path("time").path("category").path("index"), sizes.get(timeDim));
        int[] strides = new int[sizes.size()];
        int stride = 1;
        for (int d = sizes.size() - 1; d >= 0; d--) {
            strides[d] = stride;
            stride *= sizes.get(d);
        }
        Map<String, List<IneParser.Point>> byGeo = new HashMap<>();
        for (var entry : root.path("value").properties()) {
            if (!entry.getValue().isNumber()) continue;
            int flat = Integer.parseInt(entry.getKey());
            String geo = geos[(flat / strides[geoDim]) % sizes.get(geoDim)];
            String time = times[(flat / strides[timeDim]) % sizes.get(timeDim)];
            byGeo.computeIfAbsent(geo, g -> new ArrayList<>()).add(new IneParser.Point(time, entry.getValue().doubleValue()));
        }
        byGeo.values().forEach(list -> list.sort(Comparator.comparing(IneParser.Point::period)));
        return byGeo;
    }

    /** {"ES": 1, "EU27_2020": 0} -> ["EU27_2020", "ES"] */
    private static String[] labels(JsonNode index, int size) {
        String[] labels = new String[size];
        for (var entry : index.properties()) labels[entry.getValue().asInt()] = entry.getKey();
        return labels;
    }
}
