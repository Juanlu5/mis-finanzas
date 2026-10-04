package com.misfinanzas.web;

import com.misfinanzas.entity.AppUser;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.AppUserRepository;
import com.misfinanzas.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static com.misfinanzas.TestMoney.eur;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

/**
 * Boots the whole app against a throw-away data dir (SQLite file + seeded categories/rules/admin).
 * Every test runs in a transaction that is rolled back, so tests don't see each other's rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
abstract class ApiTestBase {

    static final String PASSWORD = "test-pass-123";

    private static final Path DATA_DIR = createDataDir();

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("finanzas-test-");
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
    protected MockMvc mockMvc;
    @Autowired
    protected TransactionRepository transactionRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private int keyCounter;

    /** The seed prints a random admin password once; replace it with a known one (rolled back after each test). */
    @BeforeEach
    void knownAdminPassword() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        appUserRepository.saveAndFlush(admin);
    }

    protected RequestPostProcessor auth() {
        return httpBasic("admin", PASSWORD);
    }

    protected Transaction saveTx(String date, String description, double amount, String category) {
        Transaction t = new Transaction();
        t.setDate(LocalDate.parse(date));
        t.setAccountingMonth(Transaction.monthKey(t.getDate()));
        t.setDescription(description);
        t.setAmount(eur(amount));
        t.setCategory(category);
        t.setAccount("Principal");
        t.setDedupeKey("test-key-" + keyCounter++);
        return transactionRepository.saveAndFlush(t);
    }
}
