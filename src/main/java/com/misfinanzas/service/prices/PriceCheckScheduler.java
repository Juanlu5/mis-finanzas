package com.misfinanzas.service.prices;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Checks every tracked price a couple of minutes after the app starts and then every few hours, while it runs
 * (with the PC off nothing is checked; the next start catches up). {@code app.prices.scheduled=false} turns it
 * off (tests do, so they never reach real shops).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.prices.scheduled", havingValue = "true", matchIfMissing = true)
public class PriceCheckScheduler {

    private final PriceTrackingService service;

    public PriceCheckScheduler(PriceTrackingService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${app.prices.first-check-after:PT2M}", fixedDelayString = "${app.prices.check-every:PT6H}")
    public void checkAll() {
        service.checkAll();
    }
}
