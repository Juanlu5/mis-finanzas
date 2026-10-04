package com.misfinanzas.service.economy;

import com.misfinanzas.entity.EconomyPoint;
import com.misfinanzas.entity.EconomySource;
import com.misfinanzas.repository.EconomyPointRepository;
import com.misfinanzas.repository.EconomySourceRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.misfinanzas.service.economy.EconomyService.*;

/**
 * What the Economy view shows for one region, from the stored points (never from the network, so it answers at
 * once). Indicators the INE only publishes for Spain as a whole (the rent cap, single products) and the
 * electricity price (the same in the whole peninsula) come for Spain whatever the region; each series says which
 * region it is. A region without data for an indicator gets it as null.
 */
@Service
public class EconomyReport {

    private static final Map<String, String> SOURCE_NAMES = Map.of(
            SOURCE_INE, "INE (Instituto Nacional de Estadística)",
            SOURCE_FUEL, "Ministerio para la Transición Ecológica (precios de carburantes)",
            SOURCE_POWER, "Red Eléctrica (precio de la luz, PVPC)",
            SOURCE_BDE, "Banco de España (Euríbor)",
            SOURCE_ECB, "Banco Central Europeo (tipos de interés y de cambio)",
            SOURCE_EUROSTAT, "Eurostat (España frente a la UE)");
    private static final int TOP_ITEMS = 5;

    private final EconomyPointRepository points;
    private final EconomySourceRepository sources;
    private Clock clock = Clock.system(SPAIN);

    public EconomyReport(EconomyPointRepository points, EconomySourceRepository sources) {
        this.points = points;
        this.sources = sources;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Oldest first. {@code arrived_at}: when the newest point was first stored. */
    public record Series(String region, Instant arrived_at, List<Point> points) {
    }

    public record Point(String period, double value) {
    }

    public record Group(String name, Series series) {
    }

    public record Item(String name, double value) {
    }

    public record Hour(String hour, double price) {
    }

    /** A day of electricity prices, €/MWh, in Spanish local time. */
    public record PowerDay(String date, double average, List<Hour> hours) {
    }

    public record SourceStatus(String key, String name, Instant last_success_at, String last_error) {
    }

    public record RegionOption(String code, String name) {
    }

    /** Units of {@code code} one euro buys, day by day. */
    public record Currency(String code, Series series) {
    }

    /** The same figure for Spain and for the European Union. */
    public record Comparison(Series spain, Series eu) {
    }

    public record Economy(String region, List<RegionOption> regions,
                          Series inflation, List<Group> groups, String items_period, List<Item> items_up, List<Item> items_down,
                          Series rent, Series irav, Series buy, Series buy_new, Series buy_used,
                          Series petrol, Series diesel, Series power, PowerDay power_today, PowerDay power_tomorrow,
                          Series euribor, Series ecb_deposit, Series ecb_main, List<Currency> currencies,
                          Series mortgages, Series mortgage_amount, Series mortgage_rate, Series mortgage_fixed, Series mortgage_variable,
                          Series unemployment, Series unemployment_young, Series salary,
                          Comparison eu_inflation, Comparison eu_unemployment,
                          List<SourceStatus> sources) {
    }

    public Economy forRegion(Region region) {
        LocalDate today = LocalDate.now(clock.withZone(SPAIN));
        YearMonth month = YearMonth.from(today);
        String fromMonth = month.minusMonths(HISTORY_MONTHS).toString();
        String fromQuarter = "%d-Q%d".formatted(today.getYear() - HISTORY_MONTHS / 12, (today.getMonthValue() - 1) / 3 + 1);
        String fromDay = today.minusMonths(HISTORY_MONTHS).toString();

        List<Group> groups = groups(region, fromMonth);
        Series inflation = groups.stream().filter(g -> g.name().equals(GENERAL)).map(Group::series).findFirst().orElse(null);

        List<Item> items = List.of();
        String itemsPeriod = points.findTopByIndicatorStartingWithAndRegionOrderByPeriodDesc(ITEM, Region.ES.name())
                .map(EconomyPoint::getPeriod).orElse(null);
        if (itemsPeriod != null) {
            items = points.findByIndicatorStartingWithAndRegionAndPeriod(ITEM, Region.ES.name(), itemsPeriod).stream()
                    .map(p -> new Item(p.getIndicator().substring(ITEM.length()), p.getValue()))
                    .sorted(Comparator.comparingDouble(Item::value).reversed())
                    .toList();
        }
        List<Item> up = items.stream().filter(i -> i.value() > 0).limit(TOP_ITEMS).toList();
        List<Item> down = items.reversed().stream().filter(i -> i.value() < 0).limit(TOP_ITEMS).toList();

        return new Economy(region.name(), regionOptions(),
                inflation, groups.stream().filter(g -> !g.name().equals(GENERAL)).toList(), itemsPeriod, up, down,
                series(RENT, region, fromMonth), series(IRAV, Region.ES, fromMonth),
                series(BUY, region, fromQuarter), series(BUY_NEW, region, fromQuarter), series(BUY_USED, region, fromQuarter),
                series(PETROL, region, fromDay), series(DIESEL, region, fromDay),
                series(POWER, Region.ES, fromDay), powerDay(today), powerDay(today.plusDays(1)),
                series(EURIBOR, Region.ES, fromMonth), series(ECB_DEPOSIT, Region.ES, ""), series(ECB_MAIN, Region.ES, ""),
                Stream.of("USD", "GBP").map(c -> new Currency(c, series(FX + c, Region.ES, fromDay))).filter(c -> c.series() != null).toList(),
                series(MORTGAGES, region, fromMonth), series(MORTGAGE_AMOUNT, region, fromMonth),
                series(MORTGAGE_RATE, Region.ES, fromMonth), series(MORTGAGE_FIXED, Region.ES, fromMonth), series(MORTGAGE_VARIABLE, Region.ES, fromMonth),
                series(UNEMPLOYMENT, region, fromQuarter), series(UNEMPLOYMENT_YOUNG, region, fromQuarter), series(SALARY, region, fromQuarter),
                comparison(EU_INFLATION, fromMonth), comparison(EU_UNEMPLOYMENT, fromMonth),
                sourceStatuses());
    }

    private List<Group> groups(Region region, String from) {
        Map<String, List<EconomyPoint>> byGroup = new LinkedHashMap<>();
        for (EconomyPoint p : points.findByIndicatorStartingWithAndRegionAndPeriodGreaterThanEqual(IPC, region.name(), from)) {
            byGroup.computeIfAbsent(p.getIndicator().substring(IPC.length()), g -> new ArrayList<>()).add(p);
        }
        List<Group> groups = new ArrayList<>();
        byGroup.forEach((name, list) -> groups.add(new Group(name, toSeries(region, list))));
        // biggest rise first
        groups.sort(Comparator.comparingDouble((Group g) -> -g.series().points().getLast().value()));
        return groups;
    }

    private Series series(String indicator, Region region, String from) {
        return series(indicator, region.name(), from);
    }

    private Series series(String indicator, String region, String from) {
        List<EconomyPoint> list = points.findByIndicatorAndRegionAndPeriodGreaterThanEqualOrderByPeriodAsc(indicator, region, from);
        return list.isEmpty() ? null : toSeries(region, list);
    }

    private Comparison comparison(String indicator, String from) {
        Series spain = series(indicator, Region.ES.name(), from);
        Series eu = series(indicator, EU, from);
        return spain == null && eu == null ? null : new Comparison(spain, eu);
    }

    private static Series toSeries(Region region, List<EconomyPoint> list) {
        return toSeries(region.name(), list);
    }

    private static Series toSeries(String region, List<EconomyPoint> list) {
        List<EconomyPoint> sorted = list.stream().sorted(Comparator.comparing(EconomyPoint::getPeriod)).toList();
        return new Series(region, sorted.getLast().getArrivedAt(),
                sorted.stream().map(p -> new Point(p.getPeriod(), p.getValue())).toList());
    }

    private PowerDay powerDay(LocalDate day) {
        List<EconomyPoint> hours = points.findByIndicatorAndRegionAndPeriodGreaterThanEqualOrderByPeriodAsc(POWER_HOUR, Region.ES.name(), day + "T00")
                .stream().filter(p -> p.getPeriod().startsWith(day.toString())).toList();
        if (hours.size() < 23) return null;
        double average = hours.stream().mapToDouble(EconomyPoint::getValue).average().orElse(0);
        return new PowerDay(day.toString(), Math.round(average * 100) / 100.0,
                hours.stream().map(p -> new Hour(p.getPeriod().substring(11), p.getValue())).toList());
    }

    private List<SourceStatus> sourceStatuses() {
        List<SourceStatus> list = new ArrayList<>();
        for (String key : List.of(SOURCE_INE, SOURCE_FUEL, SOURCE_POWER, SOURCE_BDE, SOURCE_ECB, SOURCE_EUROSTAT)) {
            EconomySource s = sources.findById(key).orElse(null);
            list.add(new SourceStatus(key, SOURCE_NAMES.get(key), s == null ? null : s.getLastSuccessAt(), s == null ? null : s.getLastError()));
        }
        return list;
    }

    private static List<RegionOption> regionOptions() {
        return Arrays.stream(Region.values()).map(r -> new RegionOption(r.name(), r.label())).toList();
    }
}
