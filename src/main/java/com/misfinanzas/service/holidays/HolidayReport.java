package com.misfinanzas.service.holidays;

import com.misfinanzas.entity.EconomySource;
import com.misfinanzas.entity.Holiday;
import com.misfinanzas.entity.HolidayPlace;
import com.misfinanzas.repository.EconomySourceRepository;
import com.misfinanzas.repository.HolidayPlaceRepository;
import com.misfinanzas.repository.HolidayRepository;
import com.misfinanzas.repository.HolidayYearRepository;
import com.misfinanzas.service.economy.Region;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.misfinanzas.service.holidays.HolidayService.HOME;
import static com.misfinanzas.service.holidays.HolidayService.SOURCE_KEY;
import static com.misfinanzas.service.holidays.HolidayService.WORK;

/**
 * What the Holidays view shows: each of the user's places with its holidays for this year and the next, from the
 * stored data only. When a day is a holiday for more than one reason (a national one a region also lists), it
 * appears once, with the widest kind. The long weekends are worked out by the page.
 */
@Service
public class HolidayReport {

    private final HolidayRepository holidays;
    private final HolidayPlaceRepository places;
    private final HolidayYearRepository years;
    private final EconomySourceRepository sources;
    private Clock clock = Clock.system(ZoneId.of("Europe/Madrid"));

    public HolidayReport(HolidayRepository holidays, HolidayPlaceRepository places, HolidayYearRepository years,
                         EconomySourceRepository sources) {
        this.holidays = holidays;
        this.places = places;
        this.years = years;
        this.sources = sources;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** {@code id} only for the ones the user typed in (they can delete those). */
    public record Day(String date, String name, String kind, String source, Long id) {
    }

    public record Place(String role, String region, String region_name, String town_code, String town_name, List<Day> days) {
    }

    /** Whether the BOE list for the year is out, and (for a Catalan town) whether the Generalitat has its local days. */
    public record Year(int year, boolean published, Boolean locals_published) {
    }

    public record RegionOption(String code, String name) {
    }

    public record Holidays(Place work, Place home, List<Year> years, List<RegionOption> regions,
                           Instant last_success_at, String last_error) {
    }

    public Holidays report() {
        int year = LocalDate.now(clock).getYear();
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year + 1, 12, 31);
        Place work = places.findById(WORK).map(p -> place(p, from, to)).orElse(null);
        Place home = places.findById(HOME).map(p -> place(p, from, to)).orElse(null);
        String workTown = work == null ? null : work.town_code();
        List<Year> yearList = new ArrayList<>();
        for (int y : List.of(year, year + 1)) {
            boolean published = years.findById(y).map(e -> e.getBoeId() != null).orElse(false);
            Boolean locals = workTown == null ? null
                    : !holidays.findByTownCodeAndDayBetweenOrderByDay(workTown, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)).isEmpty();
            yearList.add(new Year(y, published, locals));
        }
        EconomySource status = sources.findById(SOURCE_KEY).orElse(null);
        List<RegionOption> regions = Arrays.stream(Region.values()).filter(r -> r != Region.ES)
                .map(r -> new RegionOption(r.name(), r.label())).toList();
        return new Holidays(work, home, yearList, regions,
                status == null ? null : status.getLastSuccessAt(), status == null ? null : status.getLastError());
    }

    private Place place(HolidayPlace p, LocalDate from, LocalDate to) {
        List<Holiday> all = new ArrayList<>(holidays.findByRegionAndDayBetweenOrderByDay(p.getRegion(), from, to));
        if (p.getTownCode() != null) all.addAll(holidays.findByTownCodeAndDayBetweenOrderByDay(p.getTownCode(), from, to));
        all.addAll(holidays.findByPlaceRoleAndDayBetweenOrderByDay(p.getRole(), from, to));
        Map<LocalDate, Holiday> byDay = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparing(Holiday::getDay)).forEach(h -> byDay.merge(h.getDay(), h, HolidayReport::wider));
        List<Day> days = byDay.values().stream()
                .map(h -> new Day(h.getDay().toString(), h.getName(), h.getKind(), h.getSource(), "manual".equals(h.getSource()) ? h.getId() : null))
                .toList();
        String regionName = Region.valueOf(p.getRegion()).label();
        return new Place(p.getRole(), p.getRegion(), regionName, p.getTownCode(), p.getTownName(), days);
    }

    private static Holiday wider(Holiday a, Holiday b) {
        List<String> order = List.of("national", "region", "local");
        return order.indexOf(a.getKind()) <= order.indexOf(b.getKind()) ? a : b;
    }
}
