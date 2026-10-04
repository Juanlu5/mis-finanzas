package com.misfinanzas.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lockout through the real security chain. The limiter is a singleton shared by every test in this
 * Spring context, so each test uses its own address.
 */
class LoginLockoutApiTest extends ApiTestBase {

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private void wrongPassword(String address, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mockMvc.perform(get("/api/transactions").with(httpBasic("admin", "no-es-esta")).with(from(address)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void fiveWrongPasswordsLockTheDeviceOutEvenWithTheRightOne() throws Exception {
        wrongPassword("192.168.1.201", 5);

        mockMvc.perform(get("/api/transactions").with(auth()).with(from("192.168.1.201")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.detail", containsString("Espera 15 minutos")));
    }

    @Test
    void otherDevicesKeepWorking() throws Exception {
        wrongPassword("192.168.1.202", 5);

        mockMvc.perform(get("/api/transactions").with(auth()).with(from("192.168.1.203")))
                .andExpect(status().isOk());
    }

    @Test
    void aCorrectLoginInBetweenStartsTheCountAgain() throws Exception {
        wrongPassword("192.168.1.204", 4);
        mockMvc.perform(get("/api/transactions").with(auth()).with(from("192.168.1.204"))).andExpect(status().isOk());
        wrongPassword("192.168.1.204", 4);

        mockMvc.perform(get("/api/transactions").with(auth()).with(from("192.168.1.204")))
                .andExpect(status().isOk());
    }

    @Test
    void aRequestWithoutCredentialsDoesntCount() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/transactions").with(from("192.168.1.205"))).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(get("/api/transactions").with(auth()).with(from("192.168.1.205")))
                .andExpect(status().isOk());
    }
}
