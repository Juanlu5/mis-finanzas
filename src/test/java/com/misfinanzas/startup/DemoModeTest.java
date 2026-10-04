package com.misfinanzas.startup;

import com.misfinanzas.repository.AppUserRepository;
import com.misfinanzas.repository.EconomyPointRepository;
import com.misfinanzas.repository.HolidayPlaceRepository;
import com.misfinanzas.repository.HolidayRepository;
import com.misfinanzas.repository.TrackedProductRepository;
import com.misfinanzas.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole app started the way demo.sh starts it (app.demo=true), against its own empty folder. */
@SpringBootTest(properties = "app.demo=true")
@AutoConfigureMockMvc
class DemoModeTest {

    private static final Path DATA_DIR = createDataDir();

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("finanzas-demo-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> DATA_DIR.toString());
        registry.add("logging.file.name", () -> DATA_DIR.resolve("test.log").toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    TrackedProductRepository products;
    @Autowired
    EconomyPointRepository economyPoints;
    @Autowired
    HolidayRepository holidays;
    @Autowired
    HolidayPlaceRepository holidayPlaces;

    @Test
    void startsFilledWithTheDemoData() {
        assertThat(users.findAll()).extracting("username").containsExactly("demo");
        assertThat(transactions.count()).isEqualTo(DemoTransactions.build(LocalDate.now()).size());
        assertThat(products.count()).isEqualTo(5);

        DemoDataRunner.PublicData saved = DemoDataRunner.readPublicData();
        assertThat(economyPoints.count()).isEqualTo(saved.economyPoints().size()).isGreaterThan(1000);
        assertThat(holidays.count()).isEqualTo(saved.holidays().size()).isGreaterThan(100);
        assertThat(holidayPlaces.findById("work").orElseThrow().getTownName()).isEqualTo("Barcelona");
    }

    @Test
    void logsInAsDemoAndShowsTheBalance() throws Exception {
        mockMvc.perform(get("/api/summary/current-balance").with(httpBasic("demo", "demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].account").value(DemoTransactions.ACCOUNT));
    }

    @Test
    void neverReachesTheBank() throws Exception {
        mockMvc.perform(post("/api/bank-sync/link").with(httpBasic("demo", "demo")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("demo")));
        mockMvc.perform(post("/api/bank-sync/import").with(httpBasic("demo", "demo"))
                        .param("date_from", "2026-09-01").param("date_to", "2026-09-26").param("account", "Principal"))
                .andExpect(status().isServiceUnavailable());
    }
}
