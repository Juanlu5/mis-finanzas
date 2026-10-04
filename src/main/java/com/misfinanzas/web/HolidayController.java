package com.misfinanzas.web;

import com.misfinanzas.entity.Holiday;
import com.misfinanzas.service.holidays.HolidayParsers;
import com.misfinanzas.service.holidays.HolidayReport;
import com.misfinanzas.service.holidays.HolidayService;
import com.misfinanzas.service.economy.EconomyFetchException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Holidays view. Form parameters like the rest of the app ({@code @RequestParam}, see CategoryController):
 * {@code PUT /places} sets where a calendar comes from, {@code POST /days} types in a local holiday.
 */
@RestController
@RequestMapping("/api/holidays")
public class HolidayController {

    private final HolidayService service;
    private final HolidayReport report;

    public HolidayController(HolidayService service, HolidayReport report) {
        this.service = service;
        this.report = report;
    }

    @GetMapping
    public HolidayReport.Holidays holidays() {
        return report.report();
    }

    @PutMapping("/places/{role}")
    public HolidayReport.Holidays setPlace(@PathVariable String role, @RequestParam String region,
                                           @RequestParam(value = "town_code", required = false) String townCode,
                                           @RequestParam(value = "town_name", required = false) String townName) {
        service.setPlace(role, region, townCode, townName);
        return report.report();
    }

    @PostMapping("/days")
    public Map<String, Object> addDay(@RequestParam String role, @RequestParam LocalDate date, @RequestParam String name) {
        Holiday h = service.addDay(role, date, name);
        return Map.of("id", h.getId());
    }

    @DeleteMapping("/days/{id}")
    public Map<String, Object> deleteDay(@PathVariable long id) {
        service.deleteDay(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/towns")
    public List<HolidayParsers.Town> towns() {
        try {
            return service.catalanTowns();
        } catch (EconomyFetchException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No se ha podido leer la lista de municipios: " + e.getMessage());
        }
    }
}
