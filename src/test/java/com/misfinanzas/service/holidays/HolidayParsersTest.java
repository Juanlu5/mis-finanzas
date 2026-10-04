package com.misfinanzas.service.holidays;

import com.misfinanzas.service.economy.EconomyFetchException;
import com.misfinanzas.service.economy.Region;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HolidayParsersTest {

    private static String boe2026() throws IOException {
        try (var in = HolidayParsersTest.class.getResourceAsStream("/holidays/boe-2026-trimmed.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The holidays of one region, as day -> kind. */
    private static Map<LocalDate, String> of(List<HolidayParsers.BoeHoliday> table, Region region) {
        return table.stream().filter(h -> h.kinds().containsKey(region))
                .collect(Collectors.toMap(HolidayParsers.BoeHoliday::day, h -> h.kinds().get(region)));
    }

    @Test
    void theBoeTableGivesEachRegionItsOwnDays() throws IOException {
        // rows cut from BOE-A-2025-21667, the real 2026 list
        List<HolidayParsers.BoeHoliday> table = HolidayParsers.boeTable(boe2026(), 2026);

        Map<LocalDate, String> catalonia = of(table, Region.CT);
        Map<LocalDate, String> extremadura = of(table, Region.EX);
        // everywhere: national
        assertThat(catalonia.get(LocalDate.of(2026, 1, 1))).isEqualTo("national");
        assertThat(extremadura.get(LocalDate.of(2026, 1, 6))).isEqualTo("national");
        // the region's own
        assertThat(catalonia.get(LocalDate.of(2026, 9, 11))).isEqualTo("region");
        assertThat(extremadura.get(LocalDate.of(2026, 9, 8))).isEqualTo("region");
        // Sunday holidays Extremadura moves to Monday, and Catalonia doesn't: what the free holiday APIs get wrong
        assertThat(extremadura).containsEntry(LocalDate.of(2026, 11, 2), "region").containsEntry(LocalDate.of(2026, 12, 7), "region");
        assertThat(catalonia).doesNotContainKeys(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 7));
        // a national holiday some regions kept and others swapped is that region's, not "national"
        assertThat(extremadura.get(LocalDate.of(2026, 4, 2))).isEqualTo("region");
        assertThat(catalonia).doesNotContainKey(LocalDate.of(2026, 4, 2));
        assertThat(table).filteredOn(h -> h.day().equals(LocalDate.of(2026, 12, 7))).first()
                .extracting(HolidayParsers.BoeHoliday::name).isEqualTo("Lunes siguiente al Día de la Constitución Española");
    }

    @Test
    void aDocumentWithoutTheTableIsAClearError() {
        assertThatThrownBy(() -> HolidayParsers.boeTable("<documento><texto><p>Nada</p></texto></documento>", 2026))
                .isInstanceOf(EconomyFetchException.class);
    }

    @Test
    void findsTheResolutionInADailySummary() {
        String summary = """
                {"status":{"code":"200"},"data":{"sumario":{"diario":[{"seccion":[{"departamento":[{"epigrafe":[{"item":[
                  {"identificador":"BOE-A-2025-21666","titulo":"Resolución de otra cosa"},
                  {"identificador":"BOE-A-2025-21667","titulo":"Resolución de 17 de octubre de 2025, de la Dirección General de Trabajo, por la que se publica la relación de fiestas laborales para el año 2026."}
                ]}]}]}]}]}}}
                """;
        assertThat(HolidayParsers.findResolution(summary, 2026)).isEqualTo("BOE-A-2025-21667");
        assertThat(HolidayParsers.findResolution(summary, 2027)).isNull();
    }

    @Test
    void localDaysAndTownsFromTheGeneralitat() {
        String days = """
                [{"any_calendari":"2026","data":"2026-09-24T00:00:00.000","ajuntament_o_nucli_municipal":"Barcelona","codi_municipi_ine":"08019"},
                 {"any_calendari":"2026","data":"2026-05-25T00:00:00.000","ajuntament_o_nucli_municipal":"Barcelona","codi_municipi_ine":"08019"}]
                """;
        assertThat(HolidayParsers.localDays(days)).containsExactly(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 5, 25));
        assertThat(HolidayParsers.towns(days)).first().isEqualTo(new HolidayParsers.Town("08019", "Barcelona"));
        assertThatThrownBy(() -> HolidayParsers.localDays("{\"error\":true}")).isInstanceOf(EconomyFetchException.class);
    }
}
