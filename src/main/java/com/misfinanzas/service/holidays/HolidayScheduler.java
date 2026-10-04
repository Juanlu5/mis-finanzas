package com.misfinanzas.service.holidays;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Reads the holidays a minute and a half after start-up and then every 6 hours while the app runs: the data changes
 * a couple of times a year, but the BOE search for next year's list goes on a few dozen days at a time. Off in tests
 * ({@code app.holidays.scheduled=false}).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.holidays.scheduled", havingValue = "true", matchIfMissing = true)
public class HolidayScheduler {

    private final HolidayService service;

    public HolidayScheduler(HolidayService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${app.holidays.first-check-after:PT90S}", fixedDelayString = "${app.holidays.check-every:PT6H}")
    public void refresh() {
        service.refresh();
    }
}
