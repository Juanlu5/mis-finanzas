package com.misfinanzas.service.economy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumMap;
import java.util.Map;

/**
 * Reads the Ministry's list of every petrol station's prices (sedeaplicaciones.minetur.gob.es, "ServiciosREST
 * Carburantes") into the average price per litre of 95 petrol and diesel, for Spain and each community. A plain
 * average of the stations open to the public ("Tipo Venta" P), not weighted by what each one sells.
 */
public final class FuelParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private FuelParser() {
    }

    public record Averages(Double petrol95, Double diesel) {
    }

    public static Map<Region, Averages> parse(String json) {
        JsonNode stations;
        try {
            stations = JSON.readTree(json).path("ListaEESSPrecio");
        } catch (JacksonException e) {
            throw new EconomyFetchException("El Ministerio ha devuelto algo que no se entiende.");
        }
        if (!stations.isArray() || stations.isEmpty()) throw new EconomyFetchException("El Ministerio no ha devuelto gasolineras.");
        Map<Region, double[]> sums = new EnumMap<>(Region.class); // petrol sum, petrol count, diesel sum, diesel count
        for (JsonNode station : stations) {
            String sale = station.path("Tipo Venta").asString("P");
            if (!sale.isEmpty() && !sale.equals("P")) continue;
            Double petrol = price(station.path("Precio Gasolina 95 E5").asString(""));
            Double diesel = price(station.path("Precio Gasoleo A").asString(""));
            for (Region region : new Region[]{Region.ES, Region.fromFuelId(station.path("IDCCAA").asString("")).orElse(null)}) {
                if (region == null) continue;
                double[] s = sums.computeIfAbsent(region, r -> new double[4]);
                if (petrol != null) { s[0] += petrol; s[1]++; }
                if (diesel != null) { s[2] += diesel; s[3]++; }
            }
        }
        Map<Region, Averages> averages = new EnumMap<>(Region.class);
        sums.forEach((region, s) -> averages.put(region, new Averages(average(s[0], s[1]), average(s[2], s[3]))));
        return averages;
    }

    /** "1,459" -> 1.459; blank (the station doesn't sell it) or nonsense -> null. */
    static Double price(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            double value = Double.parseDouble(text.strip().replace(',', '.'));
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double average(double sum, double count) {
        return count == 0 ? null : Math.round(sum / count * 1000) / 1000.0;
    }
}
