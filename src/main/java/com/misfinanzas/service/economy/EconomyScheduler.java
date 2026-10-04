package com.misfinanzas.service.economy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wakes the Economy view's sources up a minute after the app starts and then every hour while it runs; each
 * source decides whether its data is due (see {@link EconomyService#refreshDue()}). {@code app.economy.scheduled=false}
 * turns it off (tests do, so they never reach the real sources).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.economy.scheduled", havingValue = "true", matchIfMissing = true)
public class EconomyScheduler {

    private final EconomyService service;

    public EconomyScheduler(EconomyService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${app.economy.first-check-after:PT1M}", fixedDelayString = "${app.economy.tick:PT1H}")
    public void refresh() {
        service.refreshDue();
    }
}
