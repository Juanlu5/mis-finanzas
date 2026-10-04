package com.misfinanzas.service.holidays;

import com.misfinanzas.entity.EconomySource;
import com.misfinanzas.entity.Holiday;
import com.misfinanzas.entity.HolidayPlace;
import com.misfinanzas.entity.HolidayYear;
import com.misfinanzas.repository.EconomySourceRepository;
import com.misfinanzas.repository.HolidayPlaceRepository;
import com.misfinanzas.repository.HolidayRepository;
import com.misfinanzas.repository.HolidayYearRepository;
import com.misfinanzas.service.economy.EconomyFetchException;
import com.misfinanzas.service.economy.EconomyFetcher;
import com.misfinanzas.service.economy.Region;
import com.misfinanzas.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Holidays view's data. National and regional holidays come from the BOE's yearly resolution (published each
 * autumn for the next year, found by going through the BOE's daily summaries), which — unlike the free holiday APIs
 * — says when a region moves a holiday that falls on a Sunday; Catalan towns' local holidays from the Generalitat;
 * any other town's local holidays are typed in by the user. Covers this year and the next.
 */
@Service
public class HolidayService {

    private static final Logger log = LoggerFactory.getLogger(HolidayService.class);
    public static final String SOURCE_KEY = "holidays";
    public static final String WORK = "work";
    public static final String HOME = "home";
    private static final ZoneId SPAIN = ZoneId.of("Europe/Madrid");
    private static final String BOE_SUMMARY = "https://www.boe.es/datosabiertos/api/boe/sumario/";
    private static final String BOE_DOCUMENT = "https://www.boe.es/diario_boe/xml.php?id=";
    private static final String LOCALS = "https://analisi.transparenciacatalunya.cat/resource/b4eh-r8up.json";
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    /** Daily summaries read per refresh while looking for a resolution (~300 KB each): the search goes on next time. */
    private static final int SUMMARIES_PER_REFRESH = 40;

    private final HolidayRepository holidays;
    private final HolidayPlaceRepository places;
    private final HolidayYearRepository years;
    private final EconomySourceRepository sources;
    private final EconomyFetcher fetcher;
    private final TransactionTemplate transaction;
    private Clock clock = Clock.system(SPAIN);
    private volatile List<HolidayParsers.Town> towns;

    public HolidayService(HolidayRepository holidays, HolidayPlaceRepository places, HolidayYearRepository years,
                          EconomySourceRepository sources, EconomyFetcher fetcher, TransactionTemplate transaction) {
        this.holidays = holidays;
        this.places = places;
        this.years = years;
        this.sources = sources;
        this.fetcher = fetcher;
        this.transaction = transaction;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(SPAIN));
    }

    // ---------- refreshing ----------

    /** Looks for (and loads) this year's and next year's BOE lists, and the local holidays of the Catalan towns chosen. */
    public synchronized void refresh() {
        EconomySource status = sources.findById(SOURCE_KEY).orElseGet(() -> {
            EconomySource s = new EconomySource();
            s.setKey(SOURCE_KEY);
            return s;
        });
        Instant now = clock.instant();
        status.setLastAttemptAt(now);
        try {
            int year = today().getYear();
            loadBoe(year);
            loadBoe(year + 1);
            for (HolidayPlace place : places.findAll()) {
                if (place.getTownCode() != null) loadLocals(place.getTownCode());
            }
            status.setLastSuccessAt(now);
            status.setLastError(null);
        } catch (EconomyFetchException e) {
            log.warn("No se han podido leer los festivos: {}", e.getMessage());
            status.setLastError(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Fallo inesperado leyendo los festivos", e);
            status.setLastError("Fallo inesperado al leer los festivos: " + e.getClass().getSimpleName());
        }
        sources.save(status);
    }

    private void loadBoe(int year) {
        HolidayYear entry = years.findById(year).orElseGet(() -> {
            HolidayYear y = new HolidayYear();
            y.setYear(year);
            return y;
        });
        LocalDate first = LocalDate.of(year, 1, 1);
        LocalDate last = LocalDate.of(year, 12, 31);
        if (entry.getBoeId() != null && !holidays.findBySourceAndDayBetween("boe", first, last).isEmpty()) return;
        if (entry.getBoeId() == null) {
            try {
                entry.setBoeId(searchResolution(entry));
            } finally {
                years.save(entry); // keep how far the search got, even when a summary couldn't be read
            }
            if (entry.getBoeId() == null) return; // not published yet (or not found yet: the search goes on next time)
        }
        List<HolidayParsers.BoeHoliday> table = HolidayParsers.boeTable(
                fetcher.get(URI.create(BOE_DOCUMENT + entry.getBoeId()), "application/xml"), year);
        transaction.executeWithoutResult(s -> {
            holidays.deleteBySourceAndDayBetween("boe", first, last);
            for (HolidayParsers.BoeHoliday h : table) {
                h.kinds().forEach((region, kind) -> holidays.save(holiday(h.day(), h.name(), kind, "boe", region.name(), null, null)));
            }
        });
        log.info("Festivos de {} cargados del BOE ({})", year, entry.getBoeId());
    }

    /**
     * The resolution comes out in October or November of the year before (2026's on 28 October 2025); the BOE
     * doesn't publish on Sundays. The search goes on from where it stopped, up to January of the year itself.
     */
    private String searchResolution(HolidayYear entry) {
        int year = entry.getYear();
        LocalDate day = entry.getScannedUntil() == null ? LocalDate.of(year - 1, 10, 1) : entry.getScannedUntil().plusDays(1);
        LocalDate end = LocalDate.of(year, 1, 31).isBefore(today()) ? LocalDate.of(year, 1, 31) : today();
        for (int read = 0; read < SUMMARIES_PER_REFRESH && !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            read++;
            String summary;
            try {
                summary = fetcher.get(URI.create(BOE_SUMMARY + BASIC.format(day)));
            } catch (EconomyFetchException e) {
                // a day without a BOE is simply skipped; anything else stops the search until next time
                if (!e.getMessage().contains("HTTP 404")) throw e;
                entry.setScannedUntil(day);
                continue;
            }
            String found = HolidayParsers.findResolution(summary, year);
            entry.setScannedUntil(day);
            if (found != null) return found;
        }
        return null;
    }

    private void loadLocals(String townCode) {
        int year = today().getYear();
        List<LocalDate> days = new ArrayList<>();
        for (int y : List.of(year, year + 1)) {
            days.addAll(HolidayParsers.localDays(fetcher.get(URI.create(LOCALS + "?codi_municipi_ine=" + townCode + "&any_calendari=" + y))));
        }
        LocalDate first = LocalDate.of(year, 1, 1);
        LocalDate last = LocalDate.of(year + 1, 12, 31);
        transaction.executeWithoutResult(s -> {
            holidays.deleteBySourceAndTownCodeAndDayBetween("generalitat", townCode, first, last);
            days.stream().distinct().forEach(d -> holidays.save(holiday(d, "Fiesta local", "local", "generalitat", null, townCode, null)));
        });
    }

    private static Holiday holiday(LocalDate day, String name, String kind, String source, String region, String townCode, String role) {
        Holiday h = new Holiday();
        h.setDay(day);
        h.setName(name);
        h.setKind(kind);
        h.setSource(source);
        h.setRegion(region);
        h.setTownCode(townCode);
        h.setPlaceRole(role);
        return h;
    }

    // ---------- the user's places and typed-in days ----------

    public HolidayPlace setPlace(String role, String regionCode, String townCode, String townName) {
        requireRole(role);
        Region region;
        try {
            region = Region.valueOf(regionCode);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Comunidad desconocida: " + regionCode);
        }
        if (region == Region.ES) throw new ApiException(HttpStatus.BAD_REQUEST, "Elige una comunidad, no España entera.");
        String code = blankToNull(townCode);
        if (code != null && region != Region.CT) code = null; // only Catalan towns have published local holidays
        if (code != null && !code.matches("\\d{5}")) throw new ApiException(HttpStatus.BAD_REQUEST, "Código de municipio inválido.");
        HolidayPlace place = places.findById(role).orElseGet(HolidayPlace::new);
        place.setRole(role);
        place.setRegion(region.name());
        place.setTownCode(code);
        place.setTownName(blankToNull(townName));
        places.save(place);
        if (code != null) {
            try {
                loadLocals(code);
            } catch (EconomyFetchException e) {
                log.warn("No se han podido leer los festivos locales de {}: {}", code, e.getMessage());
            }
        }
        return place;
    }

    public Holiday addDay(String role, LocalDate day, String name) {
        requireRole(role);
        if (day == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Falta la fecha.");
        String clean = blankToNull(name);
        if (clean == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Ponle un nombre (por ejemplo, «San Isidro»).");
        if (clean.length() > 80) throw new ApiException(HttpStatus.BAD_REQUEST, "El nombre es demasiado largo.");
        return holidays.save(holiday(day, clean, "local", "manual", null, null, role));
    }

    public void deleteDay(long id) {
        Holiday h = holidays.findById(id).filter(x -> "manual".equals(x.getSource()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ese festivo no existe o no lo apuntaste tú."));
        holidays.delete(h);
    }

    /** Catalan towns (from the Generalitat's current year), for the town picker; read once per run. */
    public List<HolidayParsers.Town> catalanTowns() {
        if (towns == null) {
            String query = "$select=codi_municipi_ine,ajuntament_o_nucli_municipal&$group=codi_municipi_ine,ajuntament_o_nucli_municipal"
                    + "&$where=any_calendari='" + today().getYear() + "'&$limit=5000";
            List<HolidayParsers.Town> read = HolidayParsers.towns(fetcher.get(URI.create(LOCALS + "?" + encodeQuery(query))));
            // several entries per town when it has "nuclis" (villages) of its own: keep the town's own name
            Map<String, HolidayParsers.Town> byCode = new TreeMap<>();
            for (HolidayParsers.Town t : read) byCode.merge(t.code(), t, (a, b) -> a.name().length() <= b.name().length() ? a : b);
            towns = byCode.values().stream().sorted(Comparator.comparing(HolidayParsers.Town::name, String.CASE_INSENSITIVE_ORDER)).toList();
        }
        return towns;
    }

    private static String encodeQuery(String query) {
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (!out.isEmpty()) out.append('&');
            out.append(pair, 0, eq + 1).append(URLEncoder.encode(pair.substring(eq + 1), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private static void requireRole(String role) {
        if (!Set.of(WORK, HOME).contains(role)) throw new ApiException(HttpStatus.BAD_REQUEST, "Calendario desconocido: " + role);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
