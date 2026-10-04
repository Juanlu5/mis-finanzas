package com.misfinanzas.service.holidays;

import com.misfinanzas.repository.EconomySourceRepository;
import com.misfinanzas.repository.HolidayPlaceRepository;
import com.misfinanzas.repository.HolidayRepository;
import com.misfinanzas.repository.HolidayYearRepository;
import com.misfinanzas.service.economy.EconomyFetchException;
import com.misfinanzas.service.economy.EconomyFetcher;
import com.misfinanzas.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The BOE and the Generalitat are a mock answering what they really send: this never reaches the internet. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class HolidayServiceTest {

    private static final ZoneId SPAIN = ZoneId.of("Europe/Madrid");

    @Autowired HolidayRepository holidays;
    @Autowired HolidayPlaceRepository places;
    @Autowired HolidayYearRepository years;
    @Autowired EconomySourceRepository sources;
    @Autowired PlatformTransactionManager transactionManager;

    EconomyFetcher fetcher = mock(EconomyFetcher.class);
    HolidayService service;
    HolidayReport report;
    List<String> summariesRead = new ArrayList<>();
    Set<String> failing = Set.of();

    @BeforeEach
    void setUp() throws IOException {
        service = new HolidayService(holidays, places, years, sources, fetcher, new TransactionTemplate(transactionManager));
        service.setClock(Clock.fixed(Instant.parse("2026-09-26T08:00:00Z"), SPAIN));
        report = new HolidayReport(holidays, places, years, sources);
        report.setClock(Clock.fixed(Instant.parse("2026-09-26T08:00:00Z"), SPAIN));
        String table;
        try (var in = getClass().getResourceAsStream("/holidays/boe-2026-trimmed.xml")) {
            table = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        when(fetcher.get(any())).thenAnswer(call -> answer(call.getArgument(0)));
        when(fetcher.get(any(), any())).thenAnswer(call -> {
            URI url = call.getArgument(0);
            if (url.toString().endsWith("id=BOE-A-2025-21667")) return table;
            throw new EconomyFetchException("sin respuesta de prueba para " + url);
        });
    }

    private String answer(URI url) {
        String u = url.toString();
        if (u.contains("/sumario/")) {
            String day = u.substring(u.lastIndexOf('/') + 1);
            summariesRead.add(day);
            if (failing.contains(day)) throw new EconomyFetchException("www.boe.es ha respondido con un error (HTTP 500).");
            if (day.equals("20251013")) throw new EconomyFetchException("www.boe.es ha respondido con un error (HTTP 404).");
            if (day.equals("20251028")) return """
                    {"data":{"sumario":{"diario":[{"item":[{"identificador":"BOE-A-2025-21667",
                     "titulo":"Resolución de 17 de octubre de 2025, de la Dirección General de Trabajo, por la que se publica la relación de fiestas laborales para el año 2026."}]}]}}}""";
            return "{\"data\":{\"sumario\":{\"diario\":[]}}}";
        }
        if (u.contains("codi_municipi_ine=08019") && u.contains("any_calendari=2026")) return """
                [{"data":"2026-09-24T00:00:00.000","ajuntament_o_nucli_municipal":"Barcelona","codi_municipi_ine":"08019"},
                 {"data":"2026-05-25T00:00:00.000","ajuntament_o_nucli_municipal":"Barcelona","codi_municipi_ine":"08019"}]""";
        if (u.contains("codi_municipi_ine=08019")) return "[]";
        throw new EconomyFetchException("sin respuesta de prueba para " + u);
    }

    private List<String> days(HolidayReport.Place place) {
        return place.days().stream().map(HolidayReport.Day::date).toList();
    }

    @Test
    void findsThisYearsListInTheBoeAndLoadsIt() {
        service.refresh();

        assertThat(years.findById(2026).orElseThrow().getBoeId()).isEqualTo("BOE-A-2025-21667");
        // no Sundays read, and a day without a BOE (404) skipped rather than stopping the search
        assertThat(summariesRead).doesNotContain("20251005", "20251012").contains("20251013", "20251014").last().isEqualTo("20251028");
        // next year's list isn't out yet: its search starts in October
        assertThat(years.findById(2027).orElseThrow().getBoeId()).isNull();
        assertThat(sources.findById(HolidayService.SOURCE_KEY).orElseThrow().getLastError()).isNull();

        service.setPlace("work", "CT", "08019", "Barcelona");
        service.setPlace("home", "EX", null, "Mérida");
        HolidayReport.Holidays h = report.report();
        assertThat(days(h.work())).contains("2026-01-01", "2026-09-11", "2026-09-24", "2026-05-25").doesNotContain("2026-12-07");
        assertThat(h.work().days()).filteredOn(d -> d.date().equals("2026-09-24")).first()
                .extracting(HolidayReport.Day::kind, HolidayReport.Day::source).containsExactly("local", "generalitat");
        assertThat(days(h.home())).contains("2026-11-02", "2026-12-07", "2026-09-08").doesNotContain("2026-09-11");
        assertThat(h.years()).extracting(HolidayReport.Year::year, HolidayReport.Year::published, HolidayReport.Year::locals_published)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2026, true, true), org.assertj.core.groups.Tuple.tuple(2027, false, false));
    }

    @Test
    void aSearchThatFailsHalfwayGoesOnFromWhereItStopped() {
        failing = Set.of("20251015");
        service.refresh();
        assertThat(years.findById(2026).orElseThrow().getScannedUntil()).isEqualTo(LocalDate.of(2025, 10, 14));
        assertThat(sources.findById(HolidayService.SOURCE_KEY).orElseThrow().getLastError()).contains("HTTP 500");

        failing = Set.of();
        summariesRead.clear();
        service.refresh();

        assertThat(summariesRead).first().isEqualTo("20251015");
        assertThat(years.findById(2026).orElseThrow().getBoeId()).isEqualTo("BOE-A-2025-21667");
    }

    @Test
    void placesAreChecked() {
        assertThatThrownBy(() -> service.setPlace("work", "ES", null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.setPlace("work", "XX", null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.setPlace("office", "CT", null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.setPlace("work", "CT", "8019; drop", null)).isInstanceOf(ApiException.class);
        // a town code only means something in Catalonia
        assertThat(service.setPlace("home", "EX", "06077", "Mérida").getTownCode()).isNull();
    }

    @Test
    void localHolidaysTypedInCanBeRemovedButNotTheOfficialOnes() {
        service.setPlace("home", "EX", null, "Mérida");
        var typed = service.addDay("home", LocalDate.of(2026, 5, 15), "  San Isidro ");
        assertThat(typed.getName()).isEqualTo("San Isidro");
        assertThat(report.report().home().days()).extracting(HolidayReport.Day::id).containsExactly(typed.getId());
        assertThatThrownBy(() -> service.addDay("home", LocalDate.of(2026, 5, 15), " ")).isInstanceOf(ApiException.class);

        service.deleteDay(typed.getId());
        assertThat(report.report().home().days()).isEmpty();

        service.refresh();
        long official = holidays.findAll().getFirst().getId();
        assertThatThrownBy(() -> service.deleteDay(official)).isInstanceOf(ApiException.class);
    }
}
