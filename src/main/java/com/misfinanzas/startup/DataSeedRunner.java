package com.misfinanzas.startup;

import com.misfinanzas.entity.AppUser;
import com.misfinanzas.entity.Category;
import com.misfinanzas.entity.Rule;
import com.misfinanzas.repository.AppUserRepository;
import com.misfinanzas.repository.CategoryRepository;
import com.misfinanzas.repository.RuleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Seeds default categories/rules on first run (mirrors the old
 * seed_defaults()) and creates the initial admin account, printing its
 * one-time generated password to the console (mirrors auth.py's
 * ensure_credentials()). In the demo ({@code app.demo=true}) the account is demo/demo instead, and
 * {@link DemoDataRunner} runs after this to fill the tables.
 */
@Component
@Order(1)
public class DataSeedRunner implements CommandLineRunner {

    private static final List<Object[]> DEFAULT_CATEGORIES = List.of(
            new Object[]{"Nómina / Ingresos", "income"},
            new Object[]{"Otros ingresos", "income"},
            new Object[]{"Vivienda", "expense"},
            new Object[]{"Supermercado", "expense"},
            new Object[]{"Restaurantes", "expense"},
            new Object[]{"Transporte", "expense"},
            new Object[]{"Suscripciones", "expense"},
            new Object[]{"Ocio", "expense"},
            new Object[]{"Salud", "expense"},
            new Object[]{"Compras", "expense"},
            new Object[]{"Ahorro / Inversión", "expense"},
            new Object[]{"Otros gastos", "expense"},
            new Object[]{"Sin categorizar", "expense"}
    );

    // Package-private so the seed rules can be checked against realistic descriptions in tests.
    static final List<String[]> DEFAULT_RULES = List.of(
            new String[]{"mercadona", "Supermercado"}, new String[]{"carrefour", "Supermercado"},
            new String[]{"lidl", "Supermercado"}, new String[]{"eroski", "Supermercado"},
            new String[]{"alcampo", "Supermercado"}, new String[]{" dia ", "Supermercado"},
            new String[]{"glovo", "Restaurantes"}, new String[]{"uber eats", "Restaurantes"},
            new String[]{"just eat", "Restaurantes"}, new String[]{"restaurante", "Restaurantes"},
            new String[]{"cafeteria", "Restaurantes"}, new String[]{"cafetería", "Restaurantes"},
            new String[]{"netflix", "Suscripciones"}, new String[]{"spotify", "Suscripciones"},
            new String[]{"hbo", "Suscripciones"}, new String[]{"amazon prime", "Suscripciones"},
            new String[]{"icloud", "Suscripciones"}, new String[]{"google one", "Suscripciones"},
            new String[]{"gasolinera", "Transporte"}, new String[]{"repsol", "Transporte"},
            new String[]{"cepsa", "Transporte"}, new String[]{"renfe", "Transporte"},
            new String[]{"uber", "Transporte"}, new String[]{"cabify", "Transporte"},
            new String[]{"metro", "Transporte"}, new String[]{"emt", "Transporte"},
            new String[]{"farmacia", "Salud"}, new String[]{"nomina", "Nómina / Ingresos"},
            new String[]{"nómina", "Nómina / Ingresos"}, new String[]{"pago de nomina", "Nómina / Ingresos"},
            new String[]{"alquiler", "Vivienda"}, new String[]{"hipoteca", "Vivienda"},
            new String[]{"comunidad de propietarios", "Vivienda"}, new String[]{"amazon", "Compras"},
            new String[]{"zara", "Compras"}, new String[]{"el corte ingles", "Compras"},
            new String[]{"aldi", "Supermercado"}, new String[]{"airbnb", "Ocio"},
            new String[]{"booking.com", "Ocio"}, new String[]{"ret. efectivo", "Otros gastos"},
            new String[]{"reintegro", "Otros gastos"}, new String[]{"ingreso en efectivo", "Otros ingresos"}
    );

    private final CategoryRepository categoryRepository;
    private final RuleRepository ruleRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean demo;

    public DataSeedRunner(CategoryRepository categoryRepository, RuleRepository ruleRepository,
                           AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
                           @Value("${app.demo:false}") boolean demo) {
        this.categoryRepository = categoryRepository;
        this.ruleRepository = ruleRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.demo = demo;
    }

    @Override
    public void run(String... args) {
        seedCategories();
        seedRules();
        ensureAdminUser();
    }

    private void seedCategories() {
        if (categoryRepository.count() > 0) return;
        for (Object[] entry : DEFAULT_CATEGORIES) {
            Category category = new Category();
            category.setName((String) entry[0]);
            category.setKind((String) entry[1]);
            categoryRepository.save(category);
        }
    }

    private void seedRules() {
        if (ruleRepository.count() > 0) return;
        for (String[] entry : DEFAULT_RULES) {
            Rule rule = new Rule();
            rule.setKeyword(entry[0]);
            rule.setCategory(entry[1]);
            ruleRepository.save(rule);
        }
    }

    private void ensureAdminUser() {
        if (appUserRepository.count() > 0) return;
        String username = demo ? "demo" : "admin";
        String password = demo ? "demo" : generatePassword();
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole("ADMIN");
        appUserRepository.save(user);

        // System.out's default charset follows the console's codepage on Windows
        // (often not UTF-8), which mangles the accented characters below — force
        // UTF-8 explicitly so this one-time password prints legibly.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (demo) {
            out.println("Modo demo (datos inventados). Usuario: demo  Contraseña: demo");
            return;
        }
        out.println("=".repeat(64));
        out.println("Mis Finanzas ahora requiere iniciar sesión.");
        out.println("  Usuario:    " + username);
        out.println("  Contraseña: " + password);
        out.println("Apunta esta contraseña ahora, no se volverá a mostrar.");
        out.println("Puedes cambiarla luego en Categorías y reglas > Seguridad.");
        out.println("=".repeat(64));
    }

    private String generatePassword() {
        byte[] bytes = new byte[9];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
