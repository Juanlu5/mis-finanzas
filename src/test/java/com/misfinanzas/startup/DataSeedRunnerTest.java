package com.misfinanzas.startup;

import com.misfinanzas.entity.AppUser;
import com.misfinanzas.entity.Category;
import com.misfinanzas.repository.AppUserRepository;
import com.misfinanzas.repository.CategoryRepository;
import com.misfinanzas.repository.RuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DataSeedRunnerTest {

    @Autowired
    CategoryRepository categories;
    @Autowired
    RuleRepository rules;
    @Autowired
    AppUserRepository users;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Built by hand: as a bean it would also run at context startup, before the test controls the data. */
    private DataSeedRunner runner() {
        return new DataSeedRunner(categories, rules, users, encoder, false);
    }

    private String runCapturingConsole(DataSeedRunner runner) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            runner.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private void emptyEverything() {
        users.deleteAll();
        rules.deleteAll();
        categories.deleteAll();
    }

    @Test
    void firstRunSeedsCategoriesRulesAndAdmin() {
        emptyEverything();

        runCapturingConsole(runner());

        assertThat(categories.count()).isEqualTo(13);
        assertThat(categories.findByName("Sin categorizar")).isPresent(); // the import falls back to this name
        assertThat(categories.findByName("Nómina / Ingresos").orElseThrow().getKind()).isEqualTo("income");
        assertThat(rules.count()).isEqualTo(DataSeedRunner.DEFAULT_RULES.size());
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByUsername("admin").orElseThrow().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void printedPasswordIsTheOneStoredHashedAndTheAccentsSurvive() {
        emptyEverything();

        String console = runCapturingConsole(runner());

        Matcher m = Pattern.compile("Contraseña:\\s+(\\S+)").matcher(console);
        assertThat(m.find()).as("password line in console output:\n" + console).isTrue();
        String printed = m.group(1);
        assertThat(printed).hasSizeGreaterThanOrEqualTo(12).matches("[A-Za-z0-9_-]+");
        AppUser admin = users.findByUsername("admin").orElseThrow();
        assertThat(admin.getPasswordHash()).isNotEqualTo(printed).startsWith("$2"); // BCrypt, never plain text
        assertThat(encoder.matches(printed, admin.getPasswordHash())).isTrue();
        assertThat(console).contains("Contraseña").contains("sesión");
    }

    @Test
    void theDemoGetsAKnownAccountInsteadOfAnAdmin() {
        emptyEverything();

        String console = runCapturingConsole(new DataSeedRunner(categories, rules, users, encoder, true));

        assertThat(users.findByUsername("admin")).isEmpty();
        assertThat(encoder.matches("demo", users.findByUsername("demo").orElseThrow().getPasswordHash())).isTrue();
        assertThat(console).contains("Modo demo").contains("demo");
        assertThat(categories.count()).isEqualTo(13); // the same categories and rules as a real start
        assertThat(rules.count()).isEqualTo(DataSeedRunner.DEFAULT_RULES.size());
    }

    @Test
    void generatedPasswordsDiffer() {
        emptyEverything();
        String first = runCapturingConsole(runner());
        users.deleteAll();
        String second = runCapturingConsole(runner());

        Pattern p = Pattern.compile("Contraseña:\\s+(\\S+)");
        Matcher a = p.matcher(first), b = p.matcher(second);
        assertThat(a.find() && b.find()).isTrue();
        assertThat(a.group(1)).isNotEqualTo(b.group(1));
    }

    @Test
    void secondRunChangesNothingAndPrintsNoPassword() {
        emptyEverything();
        runCapturingConsole(runner());
        String hashBefore = users.findByUsername("admin").orElseThrow().getPasswordHash();

        String console = runCapturingConsole(runner());

        assertThat(categories.count()).isEqualTo(13);
        assertThat(rules.count()).isEqualTo(DataSeedRunner.DEFAULT_RULES.size());
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByUsername("admin").orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
        assertThat(console).doesNotContain("Contraseña");
    }

    @Test
    void doesNotReseedDefaultsOverUserEditedData() {
        emptyEverything();
        Category custom = new Category();
        custom.setName("Solo la mía");
        custom.setKind("expense");
        categories.save(custom);

        runCapturingConsole(runner());

        assertThat(categories.findAll()).extracting(Category::getName).containsExactly("Solo la mía");
        assertThat(rules.count()).isEqualTo(DataSeedRunner.DEFAULT_RULES.size()); // rules table was empty, so it is seeded
    }

    @Test
    void doesNotCreateAnAdminWhenAUserAlreadyExists() {
        emptyEverything();
        AppUser other = new AppUser();
        other.setUsername("otra");
        other.setPasswordHash(encoder.encode("x-secret"));
        other.setRole("USER");
        users.save(other);

        String console = runCapturingConsole(runner());

        assertThat(users.findAll()).extracting(AppUser::getUsername).containsExactly("otra");
        assertThat(console).doesNotContain("Contraseña");
    }
}
