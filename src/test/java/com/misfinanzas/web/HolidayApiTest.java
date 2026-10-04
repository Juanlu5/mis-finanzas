package com.misfinanzas.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only what's stored and typed in: the sources are never reached from tests (app.holidays.scheduled=false). */
class HolidayApiTest extends ApiTestBase {

    @Test
    void placesAndTypedInDaysGoThroughFormParameters() throws Exception {
        mockMvc.perform(get("/api/holidays").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work").doesNotExist())
                .andExpect(jsonPath("$.regions.length()").value(19));

        mockMvc.perform(put("/api/holidays/places/home").with(auth()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("region=EX&town_name=M%C3%A9rida"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.home.region_name").value("Extremadura"))
                .andExpect(jsonPath("$.home.town_name").value("Mérida"));

        mockMvc.perform(post("/api/holidays/days").with(auth()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("role=home&date=2026-05-15&name=San+Isidro"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/holidays").with(auth()))
                .andExpect(jsonPath("$.home.days[0].name").value("San Isidro"))
                .andExpect(jsonPath("$.home.days[0].source").value("manual"));
    }

    @Test
    void badInputIsABadRequest() throws Exception {
        mockMvc.perform(put("/api/holidays/places/work").with(auth()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("region=ES"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/holidays/days").with(auth()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("role=home&date=no-es-fecha&name=X"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/holidays/days/999").with(auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void needsThePassword() throws Exception {
        mockMvc.perform(get("/api/holidays")).andExpect(status().isUnauthorized());
    }
}
