package com.misfinanzas.service.economy;

import com.misfinanzas.entity.EconomyPoint;
import com.misfinanzas.repository.EconomyPointRepository;
import com.misfinanzas.repository.EconomySourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import static com.misfinanzas.service.economy.EconomyService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sources are a mock answering the JSON each one really sends: this never reaches the internet. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EconomyServiceTest {

    // 10:00 in Madrid on 26 September 2026
    private static final Instant NOW = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    EconomyPointRepository points;
    @Autowired
    EconomySourceRepository sources;
    @Autowired
    PlatformTransactionManager transactionManager;

    EconomyFetcher fetcher = mock(EconomyFetcher.class);
    EconomyService service;
    EconomyReport report;
    List<String> requested = new ArrayList<>();
    boolean ineDown;
    List<String> accepts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new EconomyService(points, sources, fetcher, new TransactionTemplate(transactionManager),
                Duration.ofHours(12), Duration.ofHours(6), Duration.ofHours(3), Duration.ofHours(6), Duration.ofMinutes(55));
        service.setClock(Clock.fixed(NOW, SPAIN));
        report = new EconomyReport(points, sources);
        report.setClock(Clock.fixed(NOW, SPAIN));
        when(fetcher.get(any())).thenAnswer(call -> answer(call.getArgument(0)));
        when(fetcher.getWithCurl(any())).thenAnswer(call -> answer(call.getArgument(0)));
        when(fetcher.get(any(), any())).thenAnswer(call -> {
            accepts.add(call.getArgument(1));
            return answer(call.getArgument(0));
        });
    }

    private String answer(URI url) {
        String u = url.toString();
        requested.add(u);
        if (ineDown && u.contains("servicios.ine.es")) throw new EconomyFetchException("No se ha podido conectar con servicios.ine.es (ConnectException).");
        if (u.contains("DATOS_TABLA/76136")) return """
                [{"COD":"A","Nombre":"Cataluña. Índice general. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":4.0},{"FK_Periodo":7,"Anyo":2026,"Valor":3.3}]},
                 {"COD":"B","Nombre":"Cataluña. Índice general. Índice. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":104.6}]},
                 {"COD":"C","Nombre":"Cataluña. Transporte. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":7.9}]},
                 {"COD":"D","Nombre":"Cataluña. Vestido y calzado. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":-2.1}]},
                 {"COD":"E","Nombre":"Nacional. Índice general. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":4.3}]}]""";
        if (u.contains("DATOS_TABLA/76137")) return """
                [{"COD":"RENT_CT","Nombre":"Cataluña. Alquiler de vivienda. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":1.9}]},
                 {"COD":"X","Nombre":"Cataluña. Alquiler de vivienda. Índice. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":101}]},
                 {"COD":"Y","Nombre":"Cataluña. Alimentos. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":2.4}]}]""";
        if (u.contains("DATOS_SERIE/RENT_CT")) return """
                {"COD":"RENT_CT","Nombre":"Cataluña. Alquiler de vivienda. Variación anual. ","Data":[{"FK_Periodo":7,"Anyo":2026,"Valor":2.0},{"FK_Periodo":8,"Anyo":2026,"Valor":1.9}]}""";
        if (u.contains("DATOS_TABLA/76128")) return """
                [{"COD":"I1","Nombre":"Nacional. Huevos. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":12.5}]},
                 {"COD":"I2","Nombre":"Nacional. Gasóleo. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":30.3}]},
                 {"COD":"I3","Nombre":"Nacional. Transporte aéreo. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":-16.4}]},
                 {"COD":"I4","Nombre":"Nacional. Índice general. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":4.3}]}]""";
        if (u.contains("DATOS_SERIE/IRAV1")) return """
                {"COD":"IRAV1","Nombre":"Total Nacional. Índice general. Variación anual. ","Data":[{"FK_Periodo":8,"Anyo":2026,"Valor":2.47}]}""";
        if (u.contains("DATOS_TABLA/80270")) return """
                [{"COD":"P1","Nombre":"Cataluña. General. Variación anual. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":10.1}]},
                 {"COD":"P2","Nombre":"Cataluña. Vivienda nueva. Variación anual. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":6.4}]},
                 {"COD":"P3","Nombre":"Cataluña. Vivienda segunda mano. Variación anual. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":10.6}]}]""";
        if (u.contains("DATOS_TABLA/76316")) return """
                [{"COD":"H1","Nombre":"Viviendas. Cataluña. Número de hipotecas. Base nueva. Mensual. ","Data":[{"FK_Periodo":6,"Anyo":2026,"Valor":8045}]},
                 {"COD":"H2","Nombre":"Viviendas. Cataluña. Importe de hipotecas. Base nueva. Mensual. ","Data":[{"FK_Periodo":6,"Anyo":2026,"Valor":1567905}]},
                 {"COD":"H3","Nombre":"Total fincas. Cataluña. Número de hipotecas. Base nueva. Mensual. ","Data":[{"FK_Periodo":6,"Anyo":2026,"Valor":9883}]}]""";
        if (u.contains("DATOS_TABLA/76315")) return """
                [{"COD":"R1","Nombre":"Viviendas. Tipo de interés medio. Total Nacional. Base nueva. Mensual. Total. ","Data":[{"FK_Periodo":6,"Anyo":2026,"Valor":2.96}]},
                 {"COD":"R2","Nombre":"Viviendas. Tipo de interés medio. Total Nacional. Base nueva. Mensual. Fijo. ","Data":[{"FK_Periodo":6,"Anyo":2026,"Valor":2.89}]},
                 {"COD":"R3","Nombre":"Total fincas. Tipo de interés medio. Total Nacional. Base nueva. Mensual. Total. ","Data":[{"FK_Periodo":6,"Anyo":2026,"Valor":3.26}]}]""";
        if (u.contains("DATOS_TABLA/65334")) return """
                [{"COD":"U1","Nombre":"Tasa de paro de la población. Ambos sexos. Cataluña. Total. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":7.9}]},
                 {"COD":"U2","Nombre":"Tasa de paro de la población. Ambos sexos. Cataluña. Menores de 25 años. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":16.05}]},
                 {"COD":"U3","Nombre":"Tasa de paro de la población. Cataluña. Ambos sexos. 25 y más años. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":7.12}]}]""";
        if (u.contains("DATOS_TABLA/6061")) return """
                [{"COD":"S1","Nombre":"Cataluña. Industria, construcción y servicios (excepto actividades de los hogares como empleadores y de organizaciones y organismos extraterritoriales). Coste salarial total. Costes laborales. Euros. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":2770.19}]},
                 {"COD":"S2","Nombre":"Cataluña. Industria, construcción y servicios (excepto actividades de los hogares como empleadores y de organizaciones y organismos extraterritoriales). Coste laboral total. Costes laborales. Euros. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":3719.56}]},
                 {"COD":"S3","Nombre":"Cataluña. Industria. Coste salarial total. Costes laborales. Euros. ","Data":[{"FK_Periodo":20,"Anyo":2026,"Valor":3100}]}]""";
        if (u.contains("app.bde.es")) return """
                [{"serie":"D_1NBAF472","fechas":["2026-08-01T08:15:00Z","2026-07-01T08:15:00Z"],"valores":[2.954,2.855]}]""";
        if (u.contains("data-api.ecb.europa.eu") && u.contains("/FM/")) return """
                KEY,FREQ,TIME_PERIOD,OBS_VALUE
                FM.B.U2.EUR.4F.KR.DFR.LEV,B,2026-09-16,2.5
                FM.B.U2.EUR.4F.KR.MRR_FR.LEV,B,2026-09-16,2.65
                """;
        if (u.contains("data-api.ecb.europa.eu") && u.contains("/EXR/")) return """
                KEY,FREQ,TIME_PERIOD,OBS_VALUE
                EXR.D.USD.EUR.SP00.A,D,2026-09-25,1.1403
                EXR.D.GBP.EUR.SP00.A,D,2026-09-25,0.86045
                """;
        if (u.contains("eurostat") && u.contains("prc_hicp_minr")) return """
                {"id":["freq","unit","coicop18","geo","time"],"size":[1,1,1,2,1],
                 "dimension":{"geo":{"category":{"index":{"EU27_2020":0,"ES":1}}},"time":{"category":{"index":{"2026-08":0}}}},
                 "value":{"0":3.2,"1":4.6}}""";
        if (u.contains("eurostat") && u.contains("une_rt_m")) return """
                {"id":["freq","geo","time"],"size":[1,2,1],
                 "dimension":{"geo":{"category":{"index":{"EU27_2020":0,"ES":1}}},"time":{"category":{"index":{"2026-07":0}}}},
                 "value":{"0":6.1,"1":10.0}}""";
        if (u.contains("EstacionesTerrestresHist/01-09-2026")) return fuel("1,800", "1,700");
        if (u.contains("EstacionesTerrestres/")) return fuel("1,921", "1,919");
        if (u.contains("apidatos.ree.es") && u.contains("start_date=2026-09-25")) {
            // yesterday and today whole, tomorrow not out yet
            return twoDays();
        }
        if (u.contains("apidatos.ree.es") && u.contains("start_date=2026-08-01")) return days(LocalDate.parse("2026-08-01"), 31, 80);
        throw new EconomyFetchException("sin respuesta de prueba para " + u);
    }

    private static String fuel(String petrol, String diesel) {
        return """
                {"ListaEESSPrecio":[{"IDCCAA":"09","Tipo Venta":"P","Precio Gasolina 95 E5":"%s","Precio Gasoleo A":"%s"}]}"""
                .formatted(petrol, diesel);
    }

    /** {@code hours} hours of one day at {@code price}, as Red Eléctrica sends them. */
    private static String power(LocalDate day, double price, int hours) {
        return powerDocument(hourValues(day, price, hours));
    }

    /** {@code count} whole days from {@code first}, all at {@code price}. */
    private static String days(LocalDate first, int count, double price) {
        StringJoiner all = new StringJoiner(",");
        for (int d = 0; d < count; d++) all.add(hourValues(first.plusDays(d), price, 24));
        return powerDocument(all.toString());
    }

    private static String twoDays() {
        return powerDocument(hourValues(LocalDate.parse("2026-09-25"), 100, 24) + "," + hourValues(LocalDate.parse("2026-09-26"), 150, 24));
    }

    private static String hourValues(LocalDate day, double price, int hours) {
        StringJoiner values = new StringJoiner(",");
        for (int h = 0; h < hours; h++) {
            values.add("{\"value\":%s,\"datetime\":\"%sT%02d:00:00.000+02:00\"}".formatted(price, day, h));
        }
        return values.toString();
    }

    private static String powerDocument(String values) {
        return "{\"included\":[{\"attributes\":{\"title\":\"PVPC\",\"values\":[" + values + "]}}]}";
    }

    private double value(String indicator, Region region, String period) {
        return points.findByIndicatorAndRegionAndPeriod(indicator, region.name(), period).orElseThrow().getValue();
    }

    @Test
    void storesTheIndicatorsPickedByNameFromEachSource() {

        service.refreshDue();

        assertThat(value(IPC + GENERAL, Region.CT, "2026-08")).isEqualTo(4.0);
        assertThat(value(IPC + GENERAL, Region.CT, "2026-07")).isEqualTo(3.3);
        assertThat(value(IPC + GENERAL, Region.ES, "2026-08")).isEqualTo(4.3);
        assertThat(value(IPC + "Transporte", Region.CT, "2026-08")).isEqualTo(7.9);
        // only annual rates: the index itself isn't stored
        assertThat(points.findAll()).noneMatch(p -> p.getValue() == 104.6);
        assertThat(value(RENT, Region.CT, "2026-07")).isEqualTo(2.0);
        assertThat(points.findAll()).noneMatch(p -> p.getIndicator().contains("Alimentos"));
        assertThat(value(ITEM + "Huevos", Region.ES, "2026-08")).isEqualTo(12.5);
        assertThat(points.findAll()).noneMatch(p -> p.getIndicator().equals(ITEM + GENERAL));
        assertThat(value(IRAV, Region.ES, "2026-08")).isEqualTo(2.47);
        assertThat(value(BUY, Region.CT, "2026-Q2")).isEqualTo(10.1);
        assertThat(value(BUY_USED, Region.CT, "2026-Q2")).isEqualTo(10.6);
        // fuel today, plus the first month of history
        assertThat(value(PETROL, Region.CT, "2026-09-26")).isEqualTo(1.921);
        assertThat(value(DIESEL, Region.ES, "2026-09-26")).isEqualTo(1.919);
        assertThat(value(PETROL, Region.ES, "2026-09-01")).isEqualTo(1.8);
        // electricity: each whole day's average and its hours; and a month of history
        assertThat(value(POWER, Region.ES, "2026-09-25")).isEqualTo(100);
        assertThat(value(POWER, Region.ES, "2026-09-26")).isEqualTo(150);
        assertThat(value(POWER_HOUR, Region.ES, "2026-09-26T21")).isEqualTo(150);
        assertThat(value(POWER, Region.ES, "2026-08-01")).isEqualTo(80);
        assertThat(sources.findAll()).hasSize(6).allMatch(s -> s.getLastError() == null && NOW.equals(s.getLastSuccessAt()));
    }

    @Test
    void storesMoneyJobsAndEuropeFigures() {
        service.refreshDue();

        // home mortgages only (not every kind of property), and their average: the INE gives thousands of €
        assertThat(value(MORTGAGES, Region.CT, "2026-06")).isEqualTo(8045);
        assertThat(value(MORTGAGE_AMOUNT, Region.CT, "2026-06")).isEqualTo(194892);
        assertThat(value(MORTGAGE_RATE, Region.ES, "2026-06")).isEqualTo(2.96);
        assertThat(value(MORTGAGE_FIXED, Region.ES, "2026-06")).isEqualTo(2.89);
        assertThat(value(UNEMPLOYMENT, Region.CT, "2026-Q2")).isEqualTo(7.9);
        assertThat(value(UNEMPLOYMENT_YOUNG, Region.CT, "2026-Q2")).isEqualTo(16.05);
        // wages of all sectors together, not one sector, and not the whole labour cost
        assertThat(value(SALARY, Region.CT, "2026-Q2")).isEqualTo(2770.19);
        assertThat(value(EURIBOR, Region.ES, "2026-08")).isEqualTo(2.954);
        assertThat(value(ECB_DEPOSIT, Region.ES, "2026-09-16")).isEqualTo(2.5);
        assertThat(value(ECB_MAIN, Region.ES, "2026-09-16")).isEqualTo(2.65);
        assertThat(value(FX + "USD", Region.ES, "2026-09-25")).isEqualTo(1.1403);
        assertThat(value(FX + "GBP", Region.ES, "2026-09-25")).isEqualTo(0.86045);
        assertThat(points.findByIndicatorAndRegionAndPeriod(EU_INFLATION, EU, "2026-08").orElseThrow().getValue()).isEqualTo(3.2);
        assertThat(value(EU_INFLATION, Region.ES, "2026-08")).isEqualTo(4.6);
        assertThat(points.findByIndicatorAndRegionAndPeriod(EU_UNEMPLOYMENT, EU, "2026-07").orElseThrow().getValue()).isEqualTo(6.1);
        // the ECB answers JSON to a CSV query unless asked for CSV
        assertThat(accepts).containsOnly("text/csv").hasSize(2);

        EconomyReport.Economy ct = report.forRegion(Region.CT);
        assertThat(ct.mortgages().region()).isEqualTo("CT");
        assertThat(ct.mortgage_rate().region()).isEqualTo("ES");
        assertThat(ct.currencies()).extracting(EconomyReport.Currency::code).containsExactly("USD", "GBP");
        assertThat(ct.eu_inflation().spain().region()).isEqualTo("ES");
        assertThat(ct.eu_inflation().eu().region()).isEqualTo(EU);
        assertThat(ct.sources()).extracting(EconomyReport.SourceStatus::key)
                .containsExactly(SOURCE_INE, SOURCE_FUEL, SOURCE_POWER, SOURCE_BDE, SOURCE_ECB, SOURCE_EUROSTAT);
    }

    @Test
    void aSourceIsOnlyReadAgainAfterItsInterval() {
        service.refreshDue();
        service.setClock(Clock.fixed(NOW.plus(Duration.ofHours(4)), SPAIN));

        service.refreshDue();

        // electricity (every 3 h) again, the INE (every 12 h) and fuel (every 6 h) not yet
        verify(fetcher, times(1)).get(argThat(u -> u.toString().contains("DATOS_TABLA/76136")));
        verify(fetcher, times(1)).getWithCurl(argThat(u -> u.toString().endsWith("EstacionesTerrestres/")));
        verify(fetcher, times(2)).get(argThat(u -> u.toString().contains("start_date=2026-09-25")));
    }

    @Test
    void aFailingSourceKeepsItsLastDataAndSaysWhyWhileTheOthersCarryOn() {
        service.refreshDue();
        doThrow(new EconomyFetchException("No se ha podido conectar con servicios.ine.es (ConnectException).")).when(fetcher).get(argThat(u -> u.toString().contains("servicios.ine.es")));
        service.setClock(Clock.fixed(NOW.plus(Duration.ofHours(13)), SPAIN));

        service.refreshDue();

        var ine = sources.findById(SOURCE_INE).orElseThrow();
        assertThat(ine.getLastError()).contains("servicios.ine.es");
        assertThat(ine.getLastSuccessAt()).isEqualTo(NOW);
        assertThat(value(IPC + GENERAL, Region.CT, "2026-08")).isEqualTo(4.0);
        assertThat(sources.findById(SOURCE_FUEL).orElseThrow().getLastSuccessAt()).isEqualTo(NOW.plus(Duration.ofHours(13)));
    }

    @Test
    void aFailedSourceIsTriedAgainAtTheNextHourNotAfterItsWholeInterval() {
        ineDown = true;
        service.refreshDue();
        assertThat(sources.findById(SOURCE_INE).orElseThrow().getLastError()).isNotNull();
        // back online an hour later
        ineDown = false;
        service.setClock(Clock.fixed(NOW.plus(Duration.ofHours(1)), SPAIN));

        service.refreshDue();

        assertThat(sources.findById(SOURCE_INE).orElseThrow().getLastError()).isNull();
        assertThat(value(IPC + GENERAL, Region.CT, "2026-08")).isEqualTo(4.0);
    }

    @Test
    void daysTheAppWasOffAreFilledInFromTheNewestStoredDay() {
        service.refreshDue(); // stores the 25th and 26th
        // off until the 30th: the next read starts on the 27th, not just "yesterday"
        doReturn(days(LocalDate.parse("2026-09-27"), 5, 120)).when(fetcher).get(argThat(u -> u.toString().contains("start_date=2026-09-27")));
        service.setClock(Clock.fixed(NOW.plus(Duration.ofDays(4)), SPAIN));

        service.refreshDue();

        assertThat(value(POWER, Region.ES, "2026-09-27")).isEqualTo(120);
        assertThat(value(POWER, Region.ES, "2026-09-28")).isEqualTo(120);
        // hours only from yesterday on
        assertThat(points.findByIndicatorAndRegionAndPeriod(POWER_HOUR, "ES", "2026-09-27T10")).isEmpty();
        assertThat(value(POWER_HOUR, Region.ES, "2026-09-29T10")).isEqualTo(120);
    }

    @Test
    void aRevisedValueIsUpdatedButKeepsWhenItFirstArrived() {
        service.refreshDue();
        EconomyPoint before = points.findByIndicatorAndRegionAndPeriod(PETROL, "CT", "2026-09-26").orElseThrow();
        doReturn(fuel("1,930", "1,919")).when(fetcher).getWithCurl(argThat(u -> u.toString().endsWith("EstacionesTerrestres/")));
        service.setClock(Clock.fixed(NOW.plus(Duration.ofHours(7)), SPAIN));

        service.refreshDue();

        EconomyPoint after = points.findByIndicatorAndRegionAndPeriod(PETROL, "CT", "2026-09-26").orElseThrow();
        assertThat(after.getValue()).isEqualTo(1.93);
        assertThat(after.getArrivedAt()).isEqualTo(before.getArrivedAt()).isEqualTo(NOW);
    }

    @Test
    void historyIsFilledInOneMonthPerCall() {
        doReturn(fuel("1,774", "1,700")).when(fetcher).getWithCurl(argThat(u -> u.toString().contains("EstacionesTerrestresHist/01-08-2026")));
        doReturn(days(LocalDate.parse("2026-07-01"), 31, 70)).when(fetcher).get(argThat(u -> u.toString().contains("start_date=2026-07-01")));
        service.refreshDue();
        verify(fetcher, never()).getWithCurl(argThat(u -> u.toString().contains("01-08-2026")));

        service.refreshDue();

        assertThat(value(PETROL, Region.ES, "2026-08-01")).isEqualTo(1.774);
        assertThat(value(POWER, Region.ES, "2026-07-01")).isEqualTo(70);
    }

    @Test
    void theReportGivesTheCommunitysSeriesAndSpainsWhereThereIsNoOther() {
        service.refreshDue();

        EconomyReport.Economy ct = report.forRegion(Region.CT);

        assertThat(ct.region()).isEqualTo("CT");
        assertThat(ct.inflation().points()).extracting(EconomyReport.Point::period, EconomyReport.Point::value)
                .containsExactly(tuple("2026-07", 3.3), tuple("2026-08", 4.0));
        // biggest rise first, overall inflation apart
        assertThat(ct.groups()).extracting(EconomyReport.Group::name).containsExactly("Transporte", "Vestido y calzado");
        assertThat(ct.irav().region()).isEqualTo("ES");
        assertThat(ct.rent().region()).isEqualTo("CT");
        assertThat(ct.items_period()).isEqualTo("2026-08");
        assertThat(ct.items_up()).extracting(EconomyReport.Item::name).containsExactly("Gasóleo", "Huevos");
        assertThat(ct.items_down()).extracting(EconomyReport.Item::name).containsExactly("Transporte aéreo");
        assertThat(ct.petrol().points()).extracting(EconomyReport.Point::period).containsExactly("2026-09-01", "2026-09-26");
        assertThat(ct.power_today().average()).isEqualTo(150);
        assertThat(ct.power_today().hours()).hasSize(24).first().isEqualTo(new EconomyReport.Hour("00", 150));
        assertThat(ct.power_tomorrow()).isNull();
        assertThat(ct.sources()).extracting(EconomyReport.SourceStatus::key).startsWith(SOURCE_INE, SOURCE_FUEL, SOURCE_POWER);
        // a community the INE has no rent series for gets none
        assertThat(report.forRegion(Region.MD).rent()).isNull();
    }
}
