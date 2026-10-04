package com.misfinanzas.startup;

import com.misfinanzas.entity.Rule;
import com.misfinanzas.service.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the default rules, in seed order (that's the order they get first-match priority), against realistic descriptions. */
class SeedRulesTest {

    private final CategorizationService service = new CategorizationService();
    private List<Rule> rules;

    @BeforeEach
    void loadSeedRules() {
        rules = new ArrayList<>();
        for (String[] entry : DataSeedRunner.DEFAULT_RULES) {
            Rule rule = new Rule();
            rule.setKeyword(entry[0]);
            rule.setCategory(entry[1]);
            rules.add(rule);
        }
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "COMPRA TARJ. MERCADONA 0123        | Supermercado",
            "SUPERMERCADOS DIA                  | Supermercado",
            "COMPRA DIA 1234 VALENCIA           | Supermercado",
            "GLOVO*ORDER 998                    | Restaurantes",
            "UBER EATS HELP.UBER.COM            | Restaurantes",  // must beat the generic "uber" (Transporte)
            "UBER *TRIP                         | Transporte",
            "AMAZON PRIME VIDEO                 | Suscripciones", // must beat the generic "amazon" (Compras)
            "AMAZON EU SARL                     | Compras",
            "NETFLIX.COM                        | Suscripciones",
            "TRANSFERENCIA NÓMINA EMPRESA SL    | Nómina / Ingresos",
            "PAGO DE NOMINA MARZO               | Nómina / Ingresos",
            "RET. EFECTIVO CAJERO 4521          | Otros gastos",
            "INGRESO EN EFECTIVO                | Otros ingresos",
    })
    void categorizesRealisticDescriptions(String description, String expected) {
        assertThat(service.applyRules(description, rules)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" stays uncategorized")
    @CsvSource(delimiter = '|', value = {
            "MEDIA MARKT VALENCIA",   // contains "dia " inside "media "
            "TRANSFERENCIA A PEPE",
    })
    void doesNotMisclassify(String description) {
        assertThat(service.applyRules(description, rules)).isNull();
    }
}
