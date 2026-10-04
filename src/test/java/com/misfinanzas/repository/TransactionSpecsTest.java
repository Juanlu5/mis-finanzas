package com.misfinanzas.repository;

import com.misfinanzas.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.misfinanzas.TestMoney.eur;
import static com.misfinanzas.repository.TransactionSpecs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.hikari.maximum-pool-size=1", // each pooled connection would get its own empty in-memory DB
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionSpecsTest {

    @Autowired
    TransactionRepository repository;

    private int counter;

    private Transaction save(String month, String category, String account, String description, double amount) {
        Transaction t = new Transaction();
        t.setDate(LocalDate.parse(month + "-15"));
        t.setAccountingMonth(month);
        t.setCategory(category);
        t.setAccount(account);
        t.setDescription(description);
        t.setAmount(eur(amount));
        t.setDedupeKey("key-" + counter++);
        return repository.save(t);
    }

    @BeforeEach
    void data() {
        save("2026-03", "Supermercado", "Principal", "MERCADONA 123", -45.30);
        save("2026-03", "Restaurantes", "Principal", "Glovo", -20.00);
        save("2026-04", "Supermercado", "Ahorro", "Mercadona online", -60.00);
        save("2026-04", "Nómina / Ingresos", "Principal", "Nomina abril", 1500.00);
    }

    @SafeVarargs
    private List<String> descriptions(Specification<Transaction>... specs) {
        return repository.findAll(combine(specs)).stream().map(Transaction::getDescription).sorted().toList();
    }

    // The class comment on combine() explains why Specification.allOf can't be used with nulls.

    @Test
    void combineWithoutSpecsMatchesEverything() {
        assertThat(repository.findAll(combine())).hasSize(4);
    }

    @Test
    void combineSkipsNullSpecs() {
        assertThat(repository.findAll(combine(null, null))).hasSize(4);
        assertThat(descriptions(null, monthEquals("2026-04"), null)).hasSize(2);
    }

    @Test
    void filtersWithNullValueMeanNoFilter() {
        assertThat(repository.findAll(combine(
                monthEquals(null), categoryEquals(null), accountEquals(null), descriptionContains(null),
                kindEquals(null), amountBetween(null, null)))).hasSize(4);
    }

    @Test
    void monthEqualsFiltersByAccountingMonth() {
        assertThat(descriptions(monthEquals("2026-03"))).containsExactly("Glovo", "MERCADONA 123");
    }

    @Test
    void categoryAndAccountFilters() {
        assertThat(descriptions(categoryEquals("Supermercado"))).containsExactly("MERCADONA 123", "Mercadona online");
        assertThat(descriptions(accountEquals("Ahorro"))).containsExactly("Mercadona online");
    }

    @Test
    void descriptionSearchIsCaseInsensitiveSubstring() {
        assertThat(descriptions(descriptionContains("mercadona"))).containsExactly("MERCADONA 123", "Mercadona online");
        assertThat(descriptions(descriptionContains("ONLINE"))).containsExactly("Mercadona online");
        assertThat(descriptions(descriptionContains("zzz"))).isEmpty();
    }

    @Test
    void kindSplitsBySign() {
        assertThat(descriptions(kindEquals("income"))).containsExactly("Nomina abril");
        assertThat(descriptions(kindEquals("expense"))).containsExactly("Glovo", "MERCADONA 123", "Mercadona online");
    }

    @Test
    void amountBetweenComparesTheSizeWhateverTheSignWithEitherEndOptional() {
        assertThat(descriptions(amountBetween(eur(45.30), eur(60.0)))).containsExactly("MERCADONA 123", "Mercadona online");
        assertThat(descriptions(amountBetween(eur(100.0), null))).containsExactly("Nomina abril");
        assertThat(descriptions(amountBetween(null, eur(20.0)))).containsExactly("Glovo");
        assertThat(descriptions(amountBetween(eur(20.01), eur(45.29)))).isEmpty();
    }

    @Test
    void specsAreCombinedWithAnd() {
        assertThat(descriptions(monthEquals("2026-04"), categoryEquals("Supermercado"), accountEquals("Ahorro"),
                descriptionContains("merca"))).containsExactly("Mercadona online");
        assertThat(descriptions(monthEquals("2026-03"), categoryEquals("Nómina / Ingresos"))).isEmpty();
    }

    @Test
    void amountsGoThroughSqliteAndComeBackAsExactCents() {
        Transaction saved = save("2026-05", "Ocio", "Principal", "café", -0.29);
        repository.flush();

        Transaction read = repository.findById(saved.getId()).orElseThrow();
        assertThat(read.getAmount()).isEqualTo(new BigDecimal("-0.29"));
        assertThat(read.getBalance()).isNull();
    }

    @Test
    void uniqueDedupeKeyIsEnforced() {
        Transaction duplicate = new Transaction();
        duplicate.setDate(LocalDate.of(2026, 3, 15));
        duplicate.setDescription("otro");
        duplicate.setAmount(eur(-1.0));
        duplicate.setDedupeKey("key-0");

        // SQLite's driver isn't mapped to DataIntegrityViolationException, so match on the message.
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .hasMessageContaining("UNIQUE constraint failed: transactions.dedupe_key");
    }

    @Test
    void findDistinctQueries() {
        assertThat(repository.findDistinctAccounts()).containsExactly("Ahorro", "Principal");
        assertThat(repository.findDistinctAccountingMonths()).containsExactly("2026-04", "2026-03"); // newest first
        assertThat(repository.countByCategory("Supermercado")).isEqualTo(2);
        assertThat(repository.findByDedupeKey("key-1")).isPresent();
        assertThat(repository.findByDedupeKey("nope")).isEmpty();
    }
}
