package com.misfinanzas.web;

import com.misfinanzas.entity.EconomyPoint;
import com.misfinanzas.repository.EconomyPointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reads only what's stored: the sources themselves are never reached from tests (app.economy.scheduled=false). */
class EconomyApiTest extends ApiTestBase {

    @Autowired
    EconomyPointRepository points;

    private void store(String indicator, String region, String period, double value) {
        EconomyPoint p = new EconomyPoint();
        p.setIndicator(indicator);
        p.setRegion(region);
        p.setPeriod(period);
        p.setValue(value);
        p.setArrivedAt(Instant.parse("2026-09-15T09:00:00Z"));
        points.save(p);
    }

    @Test
    void servesTheChosenCommunityWithSpainOnlyFiguresMarkedAsSpain() throws Exception {
        store("ipc:Índice general", "CT", "2026-08", 4.0);
        store("irav", "ES", "2026-08", 2.47);

        mockMvc.perform(get("/api/economy").param("region", "CT").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("CT"))
                .andExpect(jsonPath("$.inflation.region").value("CT"))
                .andExpect(jsonPath("$.inflation.points[0].value").value(4.0))
                .andExpect(jsonPath("$.irav.region").value("ES"))
                .andExpect(jsonPath("$.rent").doesNotExist())
                .andExpect(jsonPath("$.regions.length()").value(20))
                .andExpect(jsonPath("$.sources.length()").value(6));
    }

    @Test
    void anUnknownCommunityIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/economy").param("region", "XX").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Comunidad desconocida: XX"));
    }

    @Test
    void needsThePasswordLikeEverythingElse() throws Exception {
        mockMvc.perform(get("/api/economy")).andExpect(status().isUnauthorized());
    }
}
