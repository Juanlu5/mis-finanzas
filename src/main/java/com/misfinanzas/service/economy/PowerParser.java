package com.misfinanzas.service.economy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Red Eléctrica's API (apidatos.ree.es, "precios-mercados-tiempo-real") into the PVPC — the regulated
 * electricity price, the same for everyone on it in the peninsula — hour by hour, in €/MWh. Hours are Spanish
 * local time, as the API gives them. It answers a range it can't serve with 400 and an "errors" list.
 */
public final class PowerParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private PowerParser() {
    }

    public record Hour(LocalDateTime start, double eurPerMwh) {
    }

    public static List<Hour> parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new EconomyFetchException("Red Eléctrica ha devuelto algo que no se entiende.");
        }
        if (root.has("errors")) {
            throw new EconomyFetchException("Red Eléctrica no ha dado los precios: " + root.path("errors").path(0).path("detail").asString("sin motivo"));
        }
        List<Hour> hours = new ArrayList<>();
        for (JsonNode item : root.path("included")) {
            if (!"PVPC".equals(item.path("attributes").path("title").asString())) continue;
            for (JsonNode v : item.path("attributes").path("values")) {
                try {
                    hours.add(new Hour(OffsetDateTime.parse(v.path("datetime").asString()).toLocalDateTime(), v.path("value").asDouble()));
                } catch (DateTimeParseException e) {
                    // an hour without a readable time is skipped, not the whole day
                }
            }
        }
        return hours;
    }
}
