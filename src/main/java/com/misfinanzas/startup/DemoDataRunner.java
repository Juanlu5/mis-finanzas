package com.misfinanzas.startup;

import com.misfinanzas.entity.EconomyPoint;
import com.misfinanzas.entity.EconomySource;
import com.misfinanzas.entity.Holiday;
import com.misfinanzas.entity.HolidayPlace;
import com.misfinanzas.entity.HolidayYear;
import com.misfinanzas.entity.PricePoint;
import com.misfinanzas.entity.Rule;
import com.misfinanzas.entity.TrackedProduct;
import com.misfinanzas.repository.EconomyPointRepository;
import com.misfinanzas.repository.EconomySourceRepository;
import com.misfinanzas.repository.HolidayPlaceRepository;
import com.misfinanzas.repository.HolidayRepository;
import com.misfinanzas.repository.HolidayYearRepository;
import com.misfinanzas.repository.PricePointRepository;
import com.misfinanzas.repository.RuleRepository;
import com.misfinanzas.repository.TrackedProductRepository;
import com.misfinanzas.repository.TransactionRepository;
import com.misfinanzas.service.TransactionImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Fills an empty database with the demo's data ({@code app.demo=true}, see demo.sh and docs/demo.md): invented
 * movements ({@link DemoTransactions}) and tracked products, and a saved copy of the public data (Economía and
 * Festivos) taken from the real sources by tools/demo-snapshot.py, so the demo works the same without internet.
 * Runs after {@link DataSeedRunner}, whose categories and rules the movements are categorized with.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.demo", havingValue = "true")
public class DemoDataRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataRunner.class);
    static final String PUBLIC_DATA = "/demo/public-data.json.gz";

    /** Rules a user would have added for the demo's regular payees, on top of the default ones. */
    static final List<String[]> DEMO_RULES = List.of(
            new String[]{"endesa", "Vivienda"}, new String[]{"mapfre", "Vivienda"},
            new String[]{"digi spain", "Suscripciones"}, new String[]{"gimnasio", "Ocio"},
            new String[]{"cines", "Ocio"}, new String[]{"fondo indexado", "Ahorro / Inversión"},
            new String[]{"aeat", "Otros ingresos"}, new String[]{"mediamarkt", "Compras"}
    );

    private final TransactionRepository transactions;
    private final TransactionImportService importService;
    private final RuleRepository rules;
    private final TrackedProductRepository products;
    private final PricePointRepository pricePoints;
    private final EconomySourceRepository economySources;
    private final EconomyPointRepository economyPoints;
    private final HolidayRepository holidays;
    private final HolidayPlaceRepository holidayPlaces;
    private final HolidayYearRepository holidayYears;
    private final TransactionTemplate transaction;

    public DemoDataRunner(TransactionRepository transactions, TransactionImportService importService, RuleRepository rules,
                          TrackedProductRepository products, PricePointRepository pricePoints,
                          EconomySourceRepository economySources, EconomyPointRepository economyPoints,
                          HolidayRepository holidays, HolidayPlaceRepository holidayPlaces,
                          HolidayYearRepository holidayYears, TransactionTemplate transaction) {
        this.transactions = transactions;
        this.importService = importService;
        this.rules = rules;
        this.products = products;
        this.pricePoints = pricePoints;
        this.economySources = economySources;
        this.economyPoints = economyPoints;
        this.holidays = holidays;
        this.holidayPlaces = holidayPlaces;
        this.holidayYears = holidayYears;
        this.transaction = transaction;
    }

    @Override
    public void run(String... args) {
        // demo.sh starts from an empty folder each time; a demo database that already has data is left as it is
        if (transactions.count() > 0) return;
        addRules();
        var result = importService.persist(DemoTransactions.build(LocalDate.now()));
        transaction.executeWithoutResult(status -> {
            addProducts();
            loadPublicData();
        });
        log.info("Demo: {} movimientos inventados, {} productos, datos públicos guardados", result.added(), products.count());
    }

    private void addRules() {
        for (String[] entry : DEMO_RULES) {
            Rule rule = new Rule();
            rule.setKeyword(entry[0]);
            rule.setCategory(entry[1]);
            rules.save(rule);
        }
    }

    // ---------- Precios ----------

    /** A price change, {@code daysAgo} days before now. */
    private record Step(int daysAgo, String price, Boolean available) {
    }

    private void addProducts() {
        // Below the price asked for: the card that deserves attention
        addProduct("Sony WH-1000XM5 Auriculares inalámbricos", "https://www.pccomponentes.com/sony-wh-1000xm5-negro",
                "300.00", new Step(84, "379.00", true), new Step(52, "329.00", true), new Step(6, "299.00", true));
        addProduct("Garmin Forerunner 265", "https://www.amazon.es/dp/B0BS1T9J4Y",
                "300.00", new Step(70, "349.99", true), new Step(41, "329.00", true), new Step(23, "339.00", true),
                new Step(2, "319.90", true));
        // Went up again after a drop
        addProduct("Cafetera superautomática De'Longhi Magnifica S", "https://www.elcorteingles.es/electrodomesticos/delonghi-magnifica-s",
                null, new Step(60, "399.00", true), new Step(33, "299.00", true), new Step(12, "379.00", true));
        // Out of stock now
        addProduct("Zapatillas de running Kiprun KS900", "https://www.decathlon.es/es/p/zapatillas-running-kiprun-ks900",
                "80.00", new Step(45, "99.99", true), new Step(9, "99.99", false));
        // Back in stock after being sold out
        addProduct("Robot aspirador Roborock Q7 Max", "https://www.mediamarkt.es/es/product/_roborock-q7-max-negro",
                null, new Step(50, "249.00", true), new Step(20, "249.00", false), new Step(3, "239.00", true));
    }

    private void addProduct(String name, String url, String target, Step... steps) {
        Instant now = Instant.now();
        Step last = steps[steps.length - 1];
        TrackedProduct product = new TrackedProduct();
        product.setName(name);
        product.setUrl(url);
        product.setStore(url.replaceFirst("https://(www\\.)?", "").replaceFirst("/.*", ""));
        product.setCurrency("EUR");
        product.setPrice(new BigDecimal(last.price()));
        product.setAvailable(last.available());
        product.setTargetPrice(target == null ? null : new BigDecimal(target));
        product.setCreatedAt(now.minus(Duration.ofDays(steps[0].daysAgo())));
        product.setCheckedAt(now.minus(Duration.ofHours(2)));
        products.save(product);
        for (Step step : steps) {
            PricePoint point = new PricePoint();
            point.setProductId(product.getId());
            point.setCheckedAt(now.minus(Duration.ofDays(step.daysAgo())));
            point.setPrice(new BigDecimal(step.price()));
            point.setAvailable(step.available());
            pricePoints.save(point);
        }
    }

    // ---------- Economía y Festivos: the saved copy ----------

    record PublicData(List<SourceRow> economySources, List<PointRow> economyPoints, List<HolidayRow> holidays,
                      List<PlaceRow> holidayPlaces, List<YearRow> holidayYears) {
    }

    record SourceRow(String key, Instant lastAttemptAt, Instant lastSuccessAt, String lastError) {
    }

    record PointRow(String indicator, String region, String period, double value, Instant arrivedAt) {
    }

    record HolidayRow(LocalDate day, String name, String kind, String source, String region, String townCode) {
    }

    record PlaceRow(String role, String region, String townCode, String townName) {
    }

    record YearRow(int year, String boeId, LocalDate scannedUntil) {
    }

    static PublicData readPublicData() {
        try (InputStream in = new GZIPInputStream(DemoDataRunner.class.getResourceAsStream(PUBLIC_DATA))) {
            return JsonMapper.builder().build().readValue(in, PublicData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede leer " + PUBLIC_DATA, e);
        }
    }

    private void loadPublicData() {
        PublicData data = readPublicData();
        for (SourceRow row : data.economySources()) {
            EconomySource source = new EconomySource();
            source.setKey(row.key());
            source.setLastAttemptAt(row.lastAttemptAt());
            source.setLastSuccessAt(row.lastSuccessAt());
            source.setLastError(row.lastError());
            economySources.save(source);
        }
        for (PointRow row : data.economyPoints()) {
            EconomyPoint point = new EconomyPoint();
            point.setIndicator(row.indicator());
            point.setRegion(row.region());
            point.setPeriod(row.period());
            point.setValue(row.value());
            point.setArrivedAt(row.arrivedAt());
            economyPoints.save(point);
        }
        for (HolidayRow row : data.holidays()) {
            Holiday holiday = new Holiday();
            holiday.setDay(row.day());
            holiday.setName(row.name());
            holiday.setKind(row.kind());
            holiday.setSource(row.source());
            holiday.setRegion(row.region());
            holiday.setTownCode(row.townCode());
            holidays.save(holiday);
        }
        for (PlaceRow row : data.holidayPlaces()) {
            HolidayPlace place = new HolidayPlace();
            place.setRole(row.role());
            place.setRegion(row.region());
            place.setTownCode(row.townCode());
            place.setTownName(row.townName());
            holidayPlaces.save(place);
        }
        for (YearRow row : data.holidayYears()) {
            HolidayYear year = new HolidayYear();
            year.setYear(row.year());
            year.setBoeId(row.boeId());
            year.setScannedUntil(row.scannedUntil());
            holidayYears.save(year);
        }
    }
}
