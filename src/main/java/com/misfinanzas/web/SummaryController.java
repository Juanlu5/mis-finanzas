package com.misfinanzas.web;

import com.misfinanzas.service.SummaryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/summary")
public class SummaryController {

    private final SummaryService summaryService;

    public SummaryController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /**
     * One accounting month ({@code month}), a range of them ({@code from} and {@code to}, YYYY-MM), or everything;
     * {@code until} (a date) leaves out movements dated after it.
     */
    @GetMapping
    public SummaryService.Summary summary(@RequestParam(required = false) String month,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) LocalDate until) {
        Period period = period(from, to);
        return period == null ? summaryService.summary(month, until) : summaryService.summary(period.from(), period.to(), until);
    }

    /** The money there is now: the newest known balance of each account and their sum. */
    @GetMapping("/current-balance")
    public SummaryService.CurrentBalance currentBalance() {
        return summaryService.currentBalance();
    }

    /** The last {@code months} months with data, or, with {@code from} and {@code to} (YYYY-MM), every month in between. */
    @GetMapping("/monthly")
    public List<SummaryService.MonthlyTotal> monthly(@RequestParam(defaultValue = "12") int months,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        Period period = period(from, to);
        return period == null ? summaryService.summaryMonthly(months) : summaryService.summaryMonthly(period.from(), period.to());
    }

    private record Period(YearMonth from, YearMonth to) {
    }

    // Far more than any real history; only there so a typo'd year can't ask for a million empty months.
    private static final int MAX_MONTHS = 1200;

    /** The range given by {@code from} and {@code to}, or null when neither is. */
    private static Period period(String from, String to) {
        if (from == null && to == null) return null;
        if (from == null || to == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Indica el mes inicial y el final del periodo");
        }
        YearMonth start = parseMonth(from);
        YearMonth end = parseMonth(to);
        if (start.isAfter(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El mes inicial no puede ser posterior al final");
        }
        if (start.plusMonths(MAX_MONTHS).isBefore(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El periodo no puede pasar de " + MAX_MONTHS + " meses");
        }
        return new Period(start, end);
    }

    @GetMapping("/balance-history")
    public List<SummaryService.BalancePoint> balanceHistory(@RequestParam(required = false) String account,
                                                            @RequestParam(required = false) LocalDate from,
                                                            @RequestParam(required = false) LocalDate to) {
        return summaryService.balanceHistory(account, from, to);
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Formato de mes inválido, usa AAAA-MM");
        }
    }

    @GetMapping("/by-category")
    public List<SummaryService.CategoryTotal> byCategory(@RequestParam(required = false) String month,
                                                         @RequestParam(required = false) String from,
                                                         @RequestParam(required = false) String to,
                                                         @RequestParam(required = false) LocalDate until) {
        Period period = period(from, to);
        if (until != null && period == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La fecha límite solo se admite con un periodo (from y to)");
        }
        return period == null ? summaryService.summaryByCategory(month) : summaryService.summaryByCategory(period.from(), period.to(), until);
    }

    @GetMapping("/months-available")
    public List<String> monthsAvailable() {
        return summaryService.monthsAvailable();
    }
}
