package com.misfinanzas.service.economy;

import com.misfinanzas.entity.EconomyPoint;
import com.misfinanzas.entity.EconomySource;
import com.misfinanzas.repository.EconomyPointRepository;
import com.misfinanzas.repository.EconomySourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Keeps the Economy view's data: public indicators read from the INE, the Ministry's fuel prices and Red
 * Eléctrica's electricity price, stored as {@link EconomyPoint}s so the view opens at once and still shows the
 * last data when a source is down. {@link EconomyScheduler} calls {@link #refreshDue()} every hour while the app
 * runs; each source is read again only after its own interval, and the history of fuel and electricity is filled
 * in backwards one month per call (the fuel lists are ~12 MB each, so never all at once).
 *
 * <p>Downloads happen outside any database transaction (a slow source would hold SQLite's write lock); what
 * came back is then stored in one.
 */
@Service
public class EconomyService {

    private static final Logger log = LoggerFactory.getLogger(EconomyService.class);

    public static final ZoneId SPAIN = ZoneId.of("Europe/Madrid");
    /** How far back the charts go, and how far back the fuel and electricity history is filled in. */
    public static final int HISTORY_MONTHS = 24;

    public static final String SOURCE_INE = "ine";
    public static final String SOURCE_FUEL = "fuel";
    public static final String SOURCE_POWER = "power";
    public static final String SOURCE_BDE = "bde";
    public static final String SOURCE_ECB = "ecb";
    public static final String SOURCE_EUROSTAT = "eurostat";

    // indicators (EconomyPoint.indicator)
    public static final String IPC = "ipc:";              // + ECOICOP group, "ipc:Índice general" is overall inflation
    public static final String GENERAL = "Índice general";
    public static final String ITEM = "item:";            // + INE subclass (national only)
    public static final String RENT = "rent";
    public static final String IRAV = "irav";
    public static final String BUY = "buy";
    public static final String BUY_NEW = "buy.new";
    public static final String BUY_USED = "buy.used";
    public static final String PETROL = "fuel.95";        // €/litre
    public static final String DIESEL = "fuel.diesel";
    public static final String POWER = "power";           // PVPC, daily average, €/MWh
    public static final String POWER_HOUR = "power.hour"; // PVPC per hour, kept for yesterday, today and tomorrow
    public static final String MORTGAGES = "mortgage.count";       // home mortgages signed in the month
    public static final String MORTGAGE_AMOUNT = "mortgage.avg";   // their average amount, €
    public static final String MORTGAGE_RATE = "mortgage.rate";    // average interest at signing, % (Spain only)
    public static final String MORTGAGE_FIXED = "mortgage.rate.fixed";
    public static final String MORTGAGE_VARIABLE = "mortgage.rate.variable";
    public static final String UNEMPLOYMENT = "unemployment";      // %, quarterly (EPA)
    public static final String UNEMPLOYMENT_YOUNG = "unemployment.young";
    public static final String SALARY = "salary";                  // gross wage cost per worker and month, €, quarterly
    public static final String EURIBOR = "euribor";                // 12 months, monthly average, %
    public static final String ECB_DEPOSIT = "ecb.deposit";        // % from the day it changed
    public static final String ECB_MAIN = "ecb.main";
    public static final String FX = "fx:";                         // + currency, units per euro, daily
    public static final String EU_INFLATION = "hicp";              // harmonised, % a year; region ES or EU
    public static final String EU_UNEMPLOYMENT = "unemployment.monthly"; // Eurostat, seasonally adjusted; region ES or EU
    /** Region code for the European Union's figures (not a {@link Region}: it isn't one to choose). */
    public static final String EU = "EU";

    private static final String INE_API = "https://servicios.ine.es/wstempus/js/ES/";
    // INE tables (ECOICOP ver.2, since January 2026); series are picked by name within them, see IneParser
    static final String TABLE_IPC_REGIONS = "76136";   // general and groups, by community
    static final String TABLE_IPC_SUBGROUPS = "76137"; // subgroups by community: "Alquiler de vivienda"
    static final String TABLE_IPC_ITEMS = "76128";     // national subclasses
    static final String TABLE_HOUSE_PRICES = "80270";  // IPV, quarterly, by community
    static final String SERIES_IRAV = "IRAV1";
    static final String TABLE_MORTGAGES = "76316";     // mortgages by community, monthly
    static final String TABLE_MORTGAGE_RATE = "76315"; // average interest, national
    static final String TABLE_UNEMPLOYMENT = "65334";  // EPA, unemployment rate by community and age, quarterly
    static final String TABLE_SALARIES = "6061";       // ETCL, labour cost per worker by community, quarterly
    private static final String BDE_API = "https://app.bde.es/bierest/resources/srdatosapp/listaSeries?idioma=es&rango=30M&series=";
    static final String SERIES_EURIBOR = "D_1NBAF472";
    private static final String ECB_API = "https://data-api.ecb.europa.eu/service/data/";
    private static final List<String> CURRENCIES = List.of("USD", "GBP");
    private static final String EUROSTAT_API = "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/";
    private static final String EUROSTAT_EU = "EU27_2020";
    private static final String FUEL_API = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/";
    private static final String POWER_API = "https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real";
    private static final DateTimeFormatter FUEL_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
    /** Red Eléctrica serves about a month per request: a gap longer than this is left to the monthly backfill. */
    private static final int POWER_CATCH_UP_DAYS = 28;

    private final EconomyPointRepository points;
    private final EconomySourceRepository sources;
    private final EconomyFetcher fetcher;
    private final TransactionTemplate transaction;
    private final Map<String, Duration> every;
    private final Duration retryAfter;
    private Clock clock = Clock.system(SPAIN);

    public EconomyService(EconomyPointRepository points, EconomySourceRepository sources, EconomyFetcher fetcher,
                          TransactionTemplate transaction,
                          @Value("${app.economy.ine-every:PT12H}") Duration ineEvery,
                          @Value("${app.economy.fuel-every:PT6H}") Duration fuelEvery,
                          @Value("${app.economy.power-every:PT3H}") Duration powerEvery,
                          @Value("${app.economy.finance-every:PT6H}") Duration financeEvery,
                          @Value("${app.economy.retry-after:PT55M}") Duration retryAfter) {
        this.points = points;
        this.sources = sources;
        this.fetcher = fetcher;
        this.transaction = transaction;
        this.every = Map.of(SOURCE_INE, ineEvery, SOURCE_FUEL, fuelEvery, SOURCE_POWER, powerEvery,
                SOURCE_BDE, financeEvery, SOURCE_ECB, financeEvery, SOURCE_EUROSTAT, ineEvery);
        this.retryAfter = retryAfter;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Reads each source whose interval has passed, then fills in one month of missing history of each. */
    public synchronized void refreshDue() {
        refreshIfDue(SOURCE_INE, this::readIne);
        refreshIfDue(SOURCE_FUEL, this::readFuelToday);
        refreshIfDue(SOURCE_POWER, this::readPowerAroundToday);
        refreshIfDue(SOURCE_BDE, this::readBde);
        refreshIfDue(SOURCE_ECB, this::readEcb);
        refreshIfDue(SOURCE_EUROSTAT, this::readEurostat);
        backfill("combustible", this::backfillFuelMonth);
        backfill("luz", this::backfillPowerMonth);
    }

    private void refreshIfDue(String key, Supplier<List<EconomyPoint>> read) {
        EconomySource source = sources.findById(key).orElseGet(() -> {
            EconomySource s = new EconomySource();
            s.setKey(key);
            return s;
        });
        Instant now = clock.instant();
        // After a failure (no internet when the PC starts, a source down) try again at the next hourly tick rather
        // than after the whole interval: with the PC off at night, 12 hours would often mean not that day at all.
        Duration wait = source.getLastError() == null ? every.get(key) : retryAfter;
        if (source.getLastAttemptAt() != null && source.getLastAttemptAt().plus(wait).isAfter(now)) return;
        source.setLastAttemptAt(now);
        try {
            List<EconomyPoint> read1 = read.get();
            transaction.executeWithoutResult(status -> store(read1));
            source.setLastSuccessAt(now);
            source.setLastError(null);
        } catch (EconomyFetchException e) {
            log.warn("No se han podido leer los datos de economía ({}): {}", key, e.getMessage());
            source.setLastError(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Fallo inesperado leyendo los datos de economía ({})", key, e);
            source.setLastError("Fallo inesperado al leer los datos: " + e.getClass().getSimpleName());
        }
        sources.save(source);
    }

    private void backfill(String what, Supplier<List<EconomyPoint>> step) {
        try {
            List<EconomyPoint> read = step.get();
            if (!read.isEmpty()) transaction.executeWithoutResult(status -> store(read));
        } catch (RuntimeException e) {
            // only history: the next hour tries again, and nothing on screen depends on it yet
            log.warn("No se ha podido completar el histórico de {}: {}", what, e.getMessage());
        }
    }

    // ---------- INE ----------

    private List<EconomyPoint> readIne() {
        List<EconomyPoint> read = new ArrayList<>();
        for (IneParser.Series s : ineTable(TABLE_IPC_REGIONS, 25)) {
            if (!s.last().equals("Variación anual")) continue;
            Region.fromIneName(s.part(0)).ifPresent(region -> add(read, IPC + s.part(1), region, s));
        }
        // Rent is a subgroup, and the subgroups table is ~20 MB with two years of history: the latest month finds
        // each community's rent series, which are then read one by one (a few KB each).
        for (IneParser.Series s : ineTable(TABLE_IPC_SUBGROUPS, 1)) {
            if (!s.part(1).equals("Alquiler de vivienda") || !s.last().equals("Variación anual")) continue;
            Region region = Region.fromIneName(s.part(0)).orElse(null);
            if (region == null) continue;
            for (IneParser.Series history : ineSeries(s.code(), 25)) add(read, RENT, region, history);
        }
        for (IneParser.Series s : ineTable(TABLE_IPC_ITEMS, 1)) {
            if (s.part(0).equals("Nacional") && s.last().equals("Variación anual") && !s.part(1).equals(GENERAL)) {
                add(read, ITEM + s.part(1), Region.ES, s);
            }
        }
        for (IneParser.Series s : ineSeries(SERIES_IRAV, 25)) add(read, IRAV, Region.ES, s);
        for (IneParser.Series s : ineTable(TABLE_HOUSE_PRICES, 9)) {
            if (!s.last().equals("Variación anual")) continue;
            String indicator = switch (s.part(1)) {
                case "General" -> BUY;
                case "Vivienda nueva" -> BUY_NEW;
                case "Vivienda segunda mano" -> BUY_USED;
                default -> null;
            };
            if (indicator != null) Region.fromIneName(s.part(0)).ifPresent(region -> add(read, indicator, region, s));
        }
        readMortgages(read);
        for (IneParser.Series s : ineTable(TABLE_MORTGAGE_RATE, 25)) {
            if (!s.has("Viviendas") || !s.has("Tipo de interés medio")) continue;
            String indicator = switch (s.last()) {
                case "Total" -> MORTGAGE_RATE;
                case "Fijo" -> MORTGAGE_FIXED;
                case "Variable" -> MORTGAGE_VARIABLE;
                default -> null;
            };
            if (indicator != null) s.region().ifPresent(region -> add(read, indicator, region, s));
        }
        for (IneParser.Series s : ineTable(TABLE_UNEMPLOYMENT, 9)) {
            if (!s.has("Tasa de paro de la población") || !s.has("Ambos sexos")) continue;
            String indicator = s.last().equals("Total") ? UNEMPLOYMENT : s.last().equals("Menores de 25 años") ? UNEMPLOYMENT_YOUNG : null;
            if (indicator != null) s.region().ifPresent(region -> add(read, indicator, region, s));
        }
        for (IneParser.Series s : ineTable(TABLE_SALARIES, 9)) {
            // all sectors together (the long "Industria, construcción y servicios (...)" part), total wages
            if (s.has("Coste salarial total") && s.part(1).startsWith("Industria, construcción y servicios")) {
                s.region().ifPresent(region -> add(read, SALARY, region, s));
            }
        }
        if (read.isEmpty()) throw new EconomyFetchException("El INE no ha devuelto ninguno de los datos esperados.");
        return read;
    }

    /** Home mortgages per community and month: how many, and their average amount (the INE gives the total, in thousands of €). */
    private void readMortgages(List<EconomyPoint> read) {
        Map<String, Double> amounts = new java.util.HashMap<>();
        List<IneParser.Series> counts = new ArrayList<>();
        for (IneParser.Series s : ineTable(TABLE_MORTGAGES, 25)) {
            if (!s.part(0).equals("Viviendas") || s.region().isEmpty()) continue;
            if (s.has("Número de hipotecas")) counts.add(s);
            if (s.has("Importe de hipotecas")) {
                for (IneParser.Point p : s.points()) amounts.put(s.region().get() + "|" + p.period(), p.value());
            }
        }
        for (IneParser.Series s : counts) {
            Region region = s.region().get();
            add(read, MORTGAGES, region, s);
            for (IneParser.Point p : s.points()) {
                Double thousands = amounts.get(region + "|" + p.period());
                if (thousands != null && p.value() > 0) {
                    read.add(point(MORTGAGE_AMOUNT, region, p.period(), Math.round(thousands * 1000 / p.value())));
                }
            }
        }
    }

    // ---------- Bank of Spain, ECB, Eurostat ----------

    private List<EconomyPoint> readBde() {
        List<EconomyPoint> read = new ArrayList<>();
        for (IneParser.Point p : BdeParser.monthly(fetcher.get(URI.create(BDE_API + SERIES_EURIBOR)))) {
            read.add(point(EURIBOR, Region.ES, p.period(), p.value()));
        }
        return read;
    }

    private List<EconomyPoint> readEcb() {
        List<EconomyPoint> read = new ArrayList<>();
        // policy rates: one observation per change, so the last few go back years
        Map<String, List<IneParser.Point>> rates = EcbParser.parse(fetcher.get(URI.create(
                ECB_API + "FM/B.U2.EUR.4F.KR.DFR+MRR_FR.LEV?lastNObservations=8&format=csvdata"), "text/csv"));
        rates.forEach((key, points) -> {
            String indicator = key.contains(".DFR.") ? ECB_DEPOSIT : key.contains(".MRR_FR.") ? ECB_MAIN : null;
            if (indicator != null) points.forEach(p -> read.add(point(indicator, Region.ES, p.period(), p.value())));
        });
        Map<String, List<IneParser.Point>> fx = EcbParser.parse(fetcher.get(URI.create(
                ECB_API + "EXR/D." + String.join("+", CURRENCIES) + ".EUR.SP00.A?lastNObservations=520&format=csvdata"), "text/csv"));
        fx.forEach((key, points) -> CURRENCIES.stream().filter(c -> key.contains("." + c + "."))
                .findFirst().ifPresent(c -> points.forEach(p -> read.add(point(FX + c, Region.ES, p.period(), p.value())))));
        if (read.isEmpty()) throw new EconomyFetchException("El BCE no ha devuelto ninguno de los datos esperados.");
        return read;
    }

    private List<EconomyPoint> readEurostat() {
        List<EconomyPoint> read = new ArrayList<>();
        String geos = "geo=ES&geo=" + EUROSTAT_EU + "&lastTimePeriod=25";
        eurostat(read, EU_INFLATION, "prc_hicp_minr?coicop18=TOTAL&unit=RCH_A&" + geos);
        eurostat(read, EU_UNEMPLOYMENT, "une_rt_m?age=TOTAL&sex=T&unit=PC_ACT&s_adj=SA&" + geos);
        return read;
    }

    private void eurostat(List<EconomyPoint> read, String indicator, String query) {
        EurostatParser.byGeo(fetcher.get(URI.create(EUROSTAT_API + query))).forEach((geo, points) -> {
            String region = geo.equals(EUROSTAT_EU) ? EU : geo.equals("ES") ? Region.ES.name() : null;
            if (region == null) return;
            for (IneParser.Point p : points) {
                EconomyPoint point = point(indicator, Region.ES, p.period(), p.value());
                point.setRegion(region);
                read.add(point);
            }
        });
    }

    private List<IneParser.Series> ineTable(String table, int last) {
        return IneParser.parse(fetcher.get(URI.create(INE_API + "DATOS_TABLA/" + table + "?nult=" + last)));
    }

    private List<IneParser.Series> ineSeries(String code, int last) {
        return IneParser.parse(fetcher.get(URI.create(INE_API + "DATOS_SERIE/" + code + "?nult=" + last)));
    }

    private void add(List<EconomyPoint> read, String indicator, Region region, IneParser.Series series) {
        for (IneParser.Point p : series.points()) read.add(point(indicator, region, p.period(), p.value()));
    }

    // ---------- fuel ----------

    private List<EconomyPoint> readFuelToday() {
        return fuelPoints(FUEL_API + "EstacionesTerrestres/", today());
    }

    /** The 1st of each of the last months that has no price yet, newest first: one list per call. */
    private List<EconomyPoint> backfillFuelMonth() {
        YearMonth thisMonth = YearMonth.from(today());
        for (int back = 0; back < HISTORY_MONTHS; back++) {
            LocalDate day = thisMonth.minusMonths(back).atDay(1);
            if (day.isAfter(today()) || points.existsByIndicatorAndRegionAndPeriod(PETROL, Region.ES.name(), day.toString())) continue;
            return fuelPoints(FUEL_API + "EstacionesTerrestresHist/" + FUEL_DATE.format(day), day);
        }
        return List.of();
    }

    private List<EconomyPoint> fuelPoints(String url, LocalDate day) {
        List<EconomyPoint> read = new ArrayList<>();
        // curl, not Java: see EconomyFetcher
        FuelParser.parse(fetcher.getWithCurl(URI.create(url))).forEach((region, avg) -> {
            if (avg.petrol95() != null) read.add(point(PETROL, region, day.toString(), avg.petrol95()));
            if (avg.diesel() != null) read.add(point(DIESEL, region, day.toString(), avg.diesel()));
        });
        return read;
    }

    // ---------- electricity ----------

    /**
     * From the day after the newest stored daily price (the PC may have been off for days) to tomorrow (its prices
     * come out around 20:30): each whole day's average, and the hours of yesterday, today and tomorrow.
     */
    private List<EconomyPoint> readPowerAroundToday() {
        LocalDate today = today();
        LocalDate from = today.minusDays(1);
        LocalDate newest = points.findTopByIndicatorAndRegionOrderByPeriodDesc(POWER, Region.ES.name())
                .map(p -> LocalDate.parse(p.getPeriod())).orElse(null);
        if (newest != null && newest.plusDays(1).isBefore(from)) {
            from = newest.plusDays(1).isBefore(today.minusDays(POWER_CATCH_UP_DAYS)) ? today.minusDays(POWER_CATCH_UP_DAYS) : newest.plusDays(1);
        }
        List<EconomyPoint> read = powerPoints(from, today.plusDays(1), today.minusDays(1));
        transaction.executeWithoutResult(status -> points.deleteByIndicatorAndPeriodLessThan(POWER_HOUR, HOUR.format(today.minusDays(1).atStartOfDay())));
        return read;
    }

    /**
     * The newest past month missing daily prices, read whole (the API serves up to about a month at a time). One
     * missing day is tolerated, so a day the source itself lacks isn't asked for again every hour forever.
     */
    private List<EconomyPoint> backfillPowerMonth() {
        YearMonth thisMonth = YearMonth.from(today());
        for (int back = 1; back <= HISTORY_MONTHS; back++) {
            YearMonth month = thisMonth.minusMonths(back);
            long stored = points.countByIndicatorAndRegionAndPeriodStartingWith(POWER, Region.ES.name(), month + "-");
            if (stored < month.lengthOfMonth() - 1) return powerPoints(month.atDay(1), month.atEndOfMonth(), null);
        }
        return List.of();
    }

    /** {@code hoursFrom}: also keep the hours of the days from this one on (null: daily averages only). */
    private List<EconomyPoint> powerPoints(LocalDate from, LocalDate to, LocalDate hoursFrom) {
        String url = POWER_API + "?start_date=" + from + "T00:00&end_date=" + to + "T23:59&time_trunc=hour";
        List<PowerParser.Hour> hours = PowerParser.parse(fetcher.get(URI.create(url)));
        if (hours.isEmpty()) throw new EconomyFetchException("Red Eléctrica no ha devuelto precios de la luz.");
        List<EconomyPoint> read = new ArrayList<>();
        Map<LocalDate, List<Double>> byDay = new TreeMap<>();
        for (PowerParser.Hour h : hours) {
            byDay.computeIfAbsent(h.start().toLocalDate(), d -> new ArrayList<>()).add(h.eurPerMwh());
            if (hoursFrom != null && !h.start().toLocalDate().isBefore(hoursFrom)) {
                read.add(point(POWER_HOUR, Region.ES, HOUR.format(h.start()), h.eurPerMwh()));
            }
        }
        // only whole days (23 or 25 hours when the clocks change): half of today isn't today's price
        byDay.forEach((day, prices) -> {
            if (prices.size() >= 23) {
                double average = prices.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                read.add(point(POWER, Region.ES, day.toString(), Math.round(average * 100) / 100.0));
            }
        });
        return read;
    }

    // ---------- storing ----------

    private EconomyPoint point(String indicator, Region region, String period, double value) {
        EconomyPoint p = new EconomyPoint();
        p.setIndicator(indicator);
        p.setRegion(region.name());
        p.setPeriod(period);
        p.setValue(value);
        return p;
    }

    /** New points get today's arrival time; a known one only changes its value (the INE revises provisional data). */
    private void store(List<EconomyPoint> read) {
        Instant now = clock.instant();
        Set<String> seen = new java.util.HashSet<>();
        for (EconomyPoint p : read) {
            if (!seen.add(p.getIndicator() + "|" + p.getRegion() + "|" + p.getPeriod())) continue;
            EconomyPoint stored = points.findByIndicatorAndRegionAndPeriod(p.getIndicator(), p.getRegion(), p.getPeriod()).orElse(null);
            if (stored == null) {
                p.setArrivedAt(now);
                points.save(p);
            } else if (stored.getValue() != p.getValue()) {
                stored.setValue(p.getValue());
                points.save(stored);
            }
        }
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(SPAIN));
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(SPAIN));
    }
}
