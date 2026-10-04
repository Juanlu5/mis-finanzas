package com.misfinanzas.web;

import com.misfinanzas.service.economy.EconomyReport;
import com.misfinanzas.service.economy.Region;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Economy view: public data about Spain's economy, for the whole country or one community. */
@RestController
@RequestMapping("/api/economy")
public class EconomyController {

    private final EconomyReport report;

    public EconomyController(EconomyReport report) {
        this.report = report;
    }

    @GetMapping
    public EconomyReport.Economy economy(@RequestParam(defaultValue = "ES") String region) {
        Region chosen;
        try {
            chosen = Region.valueOf(region);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Comunidad desconocida: " + region);
        }
        return report.forRegion(chosen);
    }
}
